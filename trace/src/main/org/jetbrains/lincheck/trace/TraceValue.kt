package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.util.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID


// ======== TraceValue ========

/**
 * Represents a sealed class hierarchy of traced values.
 *
 * Depending on a particular type of traced value,
 * these values carry a snapshot of its state captured at runtime.
 *
 * High-level hierarchy:
 * ```
 * TraceValue                              sealed root
 * ├── TraceNull                           captured `null` reference
 * ├── TraceVoid                           no value — `void` method result
 * ├── TraceUnit                           captured `Unit` singleton
 * │
 * ├── TraceValueLike                      content-tracked values (no reference identity)
 * │   ├── TraceScalar                     scalar in one of the eight scalar encodings
 * │   ├── TraceString                     string
 * │   ├── TraceEnum                       enum constant — anchored to declaring enum class
 * │   └── TraceArbitraryNumber            arbitrary-precision numbers
 * │       ├── TraceArbitraryInteger       unbounded integer
 * │       └── TraceArbitraryDecimal       unbounded decimal
 * │
 * ├── TraceReferenceLike                  identity-tracked values — class descriptor + identity
 * │   ├── TraceObject                     generic object — class descriptor + identity
 * │   ├── TraceObjectSnapshot             generic object — class descriptor + identity + captured fields
 * │   ├── TraceArray                      generic array — class descriptor + identity + size
 * │   ├── TraceArraySnapshot              generic array — class descriptor + identity + size + captured elements
 * │   ├── TraceMapSnapshot                key-value container — class descriptor + identity + size + captured entries
 * │   ├── TraceTextSnapshot               text object — identity + textual snapshot
 * │   ├── TraceException                  Throwable — class descriptor + identity
 * │   └── TraceExceptionSnapshot          Throwable — class descriptor + identity + detail message + stack trace frames
 * │
 * ├── TraceTypeReference                  reference to a runtime type object, discriminated by flavour
 * │
 * ├── TraceRedacted                       typed capture slot whose sensitive content was discarded
 * │
 * ├── TraceRenderedValue                  leaf value that only carries the agent's display string
 * │
 * └── TraceMarker                         synthetic recorder-emitted markers
 *     ├── TraceUnfinishedMethodResult     method tracing cut off before the method returned
 *     └── TraceUntrackedMethodResult      method result of untracked method
 * ```
 */
sealed class TraceValue

/**
 * The class name the *producing runtime* reported for this value, or `null` when it reported none.
 *
 * Only values backed by a [ClassDescriptor] carry one: a structurally captured value names its own type.
 * The kinds that carry no descriptor — scalars, strings, arbitrary-precision numbers, type references —
 * are identified by their wire kind alone; [typeName] spells those kinds per runtime.
 *
 * Subclasses that always have a class name expose it as a non-nullable member;
 * access this extension only when working with the broad [TraceValue] type.
 */
val TraceValue.className: String? get() = when (this) {
    is TraceNull,
    is TraceMarker,
    is TraceVoid,
    is TraceUnit,
    is TraceRenderedValue,
    is TraceScalar,
    is TraceString,
    is TraceArbitraryNumber,
    is TraceTypeReference
        -> null

    is TraceRedacted      -> capturedClassName
    is TraceEnum          -> className
    is TraceReferenceLike -> className
}

/**
 * The captured [ClassDescriptor] for values that carry a producing-runtime class identity
 * ([TraceReferenceLike] subclasses and [TraceEnum]);
 * `null` for every other kind, which is identified by its wire kind and needs no descriptor.
 *
 * Also `null` for [TraceRedacted]: a redacted value's own [TraceRedacted.classDescriptor] is reachable
 * only through the concrete type.
 */
val TraceValue.classDescriptor: ClassDescriptor? get() = when (this) {
    is TraceReferenceLike -> classDescriptor
    is TraceEnum -> classDescriptor

    // A redacted value carries its class name inline rather than a pool id,
    // so its descriptor has no id registered in the context and must not be reached through here.
    is TraceRedacted,
    is TraceNull,
    is TraceVoid,
    is TraceUnit,
    is TraceMarker,
    is TraceRenderedValue,
    is TraceScalar,
    is TraceString,
    is TraceArbitraryNumber,
    is TraceTypeReference
        -> null
}

/**
 * The captured [ClassDescriptor] id for values that carry a producing-runtime class identity
 * ([TraceReferenceLike] subclasses and [TraceEnum]);
 * `null` for every other kind, which is identified by its wire kind and needs no descriptor.
 *
 * Subclasses that always have a class id expose it as a non-nullable member;
 * access this extension only when working with the broad [TraceValue] type.
 */
val TraceValue.classId: Int? get() = when (this) {
    is TraceReferenceLike -> classId
    is TraceEnum -> classId

    // `null` keeps a kind out of the writer's descriptor pre-registration,
    // which is correct for every kind that encodes its type inline or has no type at all.
    is TraceRedacted,
    is TraceNull,
    is TraceVoid,
    is TraceUnit,
    is TraceMarker,
    is TraceRenderedValue,
    is TraceScalar,
    is TraceString,
    is TraceArbitraryNumber,
    is TraceTypeReference
        -> null
}

/**
 * Captures a live JVM value as the [TraceValue] subclass that matches its JVM type.
 *
 * JVM-specific: the dispatch below reads the value's Java class, so it is the JVM agent's entry point
 * into the runtime-neutral value model.
 *
 * Registers the [ClassDescriptor] of [value] in [context] when the value carries class identity
 * and the descriptor is not registered yet.
 *
 * When [captureToString] is `true`, captures the textual rendering of generic objects when safe.
 */
fun TraceValue(context: TraceContext, value: Any?, captureToString: Boolean = false): TraceValue = when (value) {
    // special values
    null    -> TraceNull
    else if (value.isUnit) -> TraceUnit
    else if (value === INJECTIONS_VOID_OBJECT) -> TraceVoid

    // primitives
    is Boolean  -> TraceScalar(value)
    is Byte     -> TraceScalar(value)
    is Short    -> TraceScalar(value)
    is Int      -> TraceScalar(value)
    is Long     -> TraceScalar(value)
    is Float    -> TraceScalar(value)
    is Double   -> TraceScalar(value)
    is Char     -> TraceScalar(value)

    // strings and char sequences
    is String       -> TraceString(value, truncate = true)
    is CharSequence -> TraceTextSnapshot(context, value, truncate = true)

    // exceptions
    is Throwable -> TraceException(context, value)

    // non-primitive numeric types
    is BigInteger -> TraceArbitraryInteger(value)
    is BigDecimal -> TraceArbitraryDecimal(value)

    // enum
    is Enum<*> -> TraceEnum(context, value)

    // class objects
    is Class<*>  -> TraceTypeReference(value)
    else if (value.isKClass) -> TraceKotlinTypeReference(value)

    // arrays
    is Array<*>     -> TraceArray(context, value)
    is IntArray     -> TraceArray(context, value)
    is LongArray    -> TraceArray(context, value)
    is ByteArray    -> TraceArray(context, value)
    is ShortArray   -> TraceArray(context, value)
    is CharArray    -> TraceArray(context, value)
    is FloatArray   -> TraceArray(context, value)
    is DoubleArray  -> TraceArray(context, value)
    is BooleanArray -> TraceArray(context, value)

    // generic object
    else -> TraceObject(context, value, captureToString)
}

// ======== TraceNull, TraceVoid, TraceUnit ========

/**
 * A captured `null` reference.
 *
 * Every trace-point slot that captures a value uses [TraceNull] to denote a captured `null` reference.
 * By the same convention, the receiver slot of a static method or static field access is also [TraceNull],
 * mirroring the JVM bytecode shape where `invokestatic` / `getstatic` have no `this`.
 *
 * Single instance; freely shareable across [TraceContext]s.
 */
data object TraceNull : TraceValue() {
    override fun toString(): String = "null"
}

/**
 * The "no value" marker used for void-return method results.
 *
 * Single instance; freely shareable across [TraceContext]s.
 */
data object TraceVoid : TraceValue() {
    override fun toString(): String = "void"
}

/**
 * A captured absence of value.
 * Distinct from [TraceVoid]: A `void` return is not representable as a value, whereas `Unit` is a value that carries nothing.
 *
 * Single instance; freely shareable across [TraceContext]s.
 */
data object TraceUnit : TraceValue() {
    override fun toString(): String = "Unit"
}

/**
 * A typed redaction marker that contains policy attribution but no captured content.
 *
 * It carries no length, hash, reference identity, or other value-derived metadata,
 * since each of those leaks information about the content.
 */
data class TraceRedacted(
    val classDescriptor: ClassDescriptor?,
    val templateUuid: UUID?,
    val templateName: String?,
) : TraceValue() {
    val capturedClassName: String? get() = classDescriptor?.name

    override fun toString(): String {
        val type = capturedClassName?.getSimpleClassName() ?: "unknown"
        val attribution = templateName?.let { " ($it)" }.orEmpty()
        return "[redacted: $type]$attribution"
    }
}

/**
 * A leaf value that carries only the display string which the agent produced.
 *
 * The fallback for a value an agent cannot capture structurally.
 * An agent that can read the structure sends [TraceObjectSnapshot] or [TraceArraySnapshot]
 * and registers the type in the [TraceContext] class-descriptor pool,
 * which keeps one value model for every runtime.
 */
data class TraceRenderedValue(val rendered: String) : TraceValue() {
    override fun toString(): String = rendered
}

// TODO: re-check if we can get rid of this and re-use void object from `Injections`
var INJECTIONS_VOID_OBJECT: Any? = null

// ======== TraceValueLike ========

/**
 * A sealed parent class for values recorded by their content, with no reference identity:
 * scalars, strings, arbitrary-precision numbers, and enum constants.
 *
 * Most subclasses need no [ClassDescriptor] because their wire kind already identifies the type;
 * [TraceEnum] is the exception, carrying the declaring enum class so a consumer
 * can distinguish two enums that happen to share an entry name.
 */
sealed class TraceValueLike : TraceValue()


// ======== TraceScalar ========

/**
 * A traced scalar value — one of the eight scalar encodings the trace model defines:
 * boolean, byte, short, int, long, float, double, char.
 *
 * The model is written in Kotlin, so [value] holds the scalar boxed:
 * [Boolean], [Byte], [Short], [Int], [Long], [Float], [Double], or [Char].
 * `Unit` is not a scalar — it arrives as [TraceUnit].
 *
 * Context-free — shareable across [TraceContext]s.
 */
data class TraceScalar(val value: Any) : TraceValueLike() {
    init {
        require(value.isPrimitive) {
            "Value of class ${value.javaClass.name} is not a JVM primitive"
        }
    }

    override fun toString(): String = when (value) {
        is Char -> "'$value'"
        else -> value.toString()
    }
}


// ======== TraceString ========

/**
 * A traced string value.
 *
 * Context-free — shareable across [TraceContext]s.
 */
data class TraceString(val value: String) : TraceValueLike() {
    constructor(value: String, truncate: Boolean) : this(if (truncate) value.truncateForCapture() else value)

    override fun toString(): String = "\"${value.escape()}\""
}

private fun String.escape() = this
    .replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

internal fun String.truncateForCapture(): String =
    if (length > MAX_TRSTRING_LENGTH) "${take(MAX_TRSTRING_LENGTH)}..." else this


// ======== TraceEnum ========

/**
 * Represents a traced enum constant, captured as its declaring class plus the entry [name].
 *
 * Context-anchored — the source [ClassDescriptor] identifies the enum class within a particular [TraceContext].
 *
 * [name] is nullable to accommodate pathological captures: some reflection-heavy frameworks (e.g., Mockk)
 * may instantiate enums bypassing the `Enum<*>` constructor (e.g., via the Objenesis library),
 * which leaves the final `name` field `null`.
 */
@ConsistentCopyVisibility
data class TraceEnum internal constructor(
    val classDescriptor: ClassDescriptor,
    val name: String?,
) : TraceValueLike() {
    val classId: Int get() = classDescriptor.id
    val className: String get() = classDescriptor.name

    override fun toString(): String = "${className.getSimpleClassName()}.$name"
}

/**
 * Captures a live JVM enum constant, registering its declaring class in [context] if needed.
 */
fun <E : Enum<*>> TraceEnum(context: TraceContext, value: E): TraceEnum {
    val classDescriptor = context.createAndRegisterClassDescriptor(value.javaClass.name)
    return TraceEnum(classDescriptor, value.name)
}


// ======== TraceArbitraryNumber: TraceArbitraryInteger, TraceArbitraryDecimal ========

/**
 * A sealed parent class for traced arbitrary-precision numbers, captured as their decimal rendering.
 * Subclasses split unbounded integers from unbounded decimals;
 * [value] is the rendering the producing runtime reported.
 *
 * Context-free — instances are constructed directly without a [TraceContext] and are shareable across contexts.
 */
sealed class TraceArbitraryNumber : TraceValueLike() {
    abstract val value: String
}

/**
 * A traced unbounded integer, held as its decimal rendering.
 * The [BigInteger] constructor is the JVM capture path.
 *
 * Context-free — shareable across [TraceContext]s.
 *
 * @see TraceArbitraryNumber
 */
@ConsistentCopyVisibility
data class TraceArbitraryInteger internal constructor(override val value: String) : TraceArbitraryNumber() {
    constructor(value: BigInteger) : this(value.toString())

    override fun toString(): String = value
}

/**
 * A traced unbounded decimal, held as its decimal rendering.
 * The [BigDecimal] constructor is the JVM capture path.
 *
 * Context-free — shareable across [TraceContext]s.
 *
 * @see TraceArbitraryNumber
 */
@ConsistentCopyVisibility
data class TraceArbitraryDecimal internal constructor(override val value: String) : TraceArbitraryNumber() {
    constructor(value: BigDecimal) : this(value.toString())

    override fun toString(): String = value
}


// ======== TraceReferenceLike ========

/**
 * A sealed parent class for objects tracked by their reference identity,
 * storing [classDescriptor] and [identity] captured at application runtime.
 *
 * Snapshot variants additionally carry captured content:
 * fields for objects, elements for arrays, content for text-like values.
 *
 * Subclasses are inherently context-anchored:
 * the [classDescriptor] refers to a real application class registered with the originating [TraceContext].
 */
sealed class TraceReferenceLike : TraceValue() {
    abstract val classDescriptor: ClassDescriptor

    /**
     * A stable per-object identity in the producing runtime.
     */
    abstract val identity: Long

    val classId: Int get() = classDescriptor.id
    val className: String get() = classDescriptor.name

}


// ======== TraceObject ========

/**
 * Represents a generic traced runtime object, identified by its reference identity.
 *
 * Objects of this class are tied to a specific tracing context,
 * meaning the [classDescriptor] refers to a class registered within the related [TraceContext].
 *
 * @property classDescriptor the registered descriptor of the object's runtime class.
 * @property identity the object's `System.identityHashCode` value.
 * @property rendered the safe textual rendering captured from `toString()`, if requested and available.
 * @see TraceReferenceLike
 */
@ConsistentCopyVisibility
data class TraceObject internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val rendered: String?,
) : TraceReferenceLike() {

    override fun toString(): String {
        val objectLabel = className.adornedClassNameRepresentation() + "@$identity"
        return if (rendered != null) "$objectLabel \"${rendered.escape()}\"" else objectLabel
    }
}

/**
 * Captures a live JVM object by its class and `System.identityHashCode`,
 * registering its [ClassDescriptor] in [context] if needed.
 * Also captures — if [SafeToStringCapturer] deems it safe —
 * a textual rendering produced by `obj.toString()`.
 */
fun TraceObject(context: TraceContext, obj: Any, captureToString: Boolean = false): TraceObject {
    val classDescriptor = context.createAndRegisterClassDescriptor(obj.javaClass.name)
    val rendered = if (captureToString) SafeToStringCapturer.captureToString(obj) else null
    return TraceObject(classDescriptor, System.identityHashCode(obj).toLong(), rendered)
}


// ======== TraceObjectSnapshot ========

/**
 * Represents a snapshot of a traced object captured during runtime.
 * Unlike [TraceObject] captures not only the class and identity of the object itself,
 * but also a snapshot of its fields' values at the moment of capturing.
 *
 * @property classDescriptor the registered descriptor of the object's runtime class.
 * @property identity the object's `System.identityHashCode` value.
 * @property rendered the safe textual rendering captured from `toString()`, if requested and available.
 * @property fields captured field values, keyed by field name.
 */
@ConsistentCopyVisibility
data class TraceObjectSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val rendered: String?,
    val fields: Map<String, TraceValue>,
) : TraceReferenceLike() {

    override fun toString(): String {
        val objectLabel = className.adornedClassNameRepresentation() + "@$identity"
        return if (rendered != null) "$objectLabel \"${rendered.escape()}\"" else objectLabel
    }
}

/**
 * Captures a live JVM object together with the already-read [fields], keyed by field name.
 *
 * Registers the [ClassDescriptor] of [obj] and of every field value in [context] if needed.
 */
fun TraceObjectSnapshot(
    context: TraceContext,
    obj: Any,
    fields: Map<String, Any?>,
    captureToString: Boolean = false,
): TraceObjectSnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(obj.javaClass.name)
    val trObjectMap = fields.mapValues { (_, value) -> TraceValue(context, value, captureToString) }
    return TraceObjectSnapshot(
        classDescriptor,
        System.identityHashCode(obj).toLong(),
        if (captureToString) SafeToStringCapturer.captureToString(obj) else null,
        trObjectMap,
    )
}


// ======== TraceArray ========

/**
 * Represents a traced array object, identified by its reference identity.
 * In addition to the array's class and identity also captures the array's size.
 */
@ConsistentCopyVisibility
data class TraceArray internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val totalSize: Int,
) : TraceReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}

/**
 * Captures a live JVM array by its class, `System.identityHashCode`, and length.
 *
 * @throws IllegalArgumentException if [array] is not a JVM array.
 */
fun TraceArray(context: TraceContext, array: Any): TraceArray {
    require(array.javaClass.isArray) {
        "Value of class ${array.javaClass.name} is not a JVM array"
    }
    val classDescriptor = context.createAndRegisterClassDescriptor(array.javaClass.name)
    val size = getArraySize(array)
    return TraceArray(classDescriptor, System.identityHashCode(array).toLong(), size)
}


// ======== TraceArraySnapshot ========

/**
 * Represents a snapshot of a traced array captured at runtime.
 * Unlike [TraceArray] captures not only the class, identity, and size of the array object itself,
 * but also a snapshot of its elements' values at the moment of capturing.
 */
@ConsistentCopyVisibility
data class TraceArraySnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val totalSize: Int,
    val capturedElements: List<TraceValue>,
) : TraceReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}

/**
 * Captures a live JVM array together with the already-read [elements].
 *
 * Registers the [ClassDescriptor] of [array] and of every element in [context] if needed.
 *
 * @param size the array's full length, which exceeds `elements.size` when the caller capped how many
 *   elements it read.
 */
fun TraceArraySnapshot(
    context: TraceContext,
    array: Any,
    size: Int,
    elements: List<Any?>,
    captureToString: Boolean = false,
): TraceArraySnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(array.javaClass.name)
    val elementsAsTRValues = elements.map { value -> TraceValue(context, value, captureToString) }
    return TraceArraySnapshot(classDescriptor, System.identityHashCode(array).toLong(), size, elementsAsTRValues)
}


// ======== TraceException ========

/**
 * Represents a traced exception object, identified by its reference identity.
 *
 * @see TraceReferenceLike
 * @see TraceExceptionSnapshot
 */
@ConsistentCopyVisibility
data class TraceException internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
) : TraceReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}

/**
 * Captures a live JVM [Throwable] by its class and `System.identityHashCode`,
 * registering its [ClassDescriptor] in [context] if needed.
 */
fun TraceException(context: TraceContext, throwable: Throwable): TraceException {
    val classDescriptor = context.createAndRegisterClassDescriptor(throwable.javaClass.name)
    return TraceException(classDescriptor, System.identityHashCode(throwable).toLong())
}


// ======== TraceExceptionSnapshot ========

/**
 * Represents a snapshot of a traced exception captured at runtime.
 * Unlike [TraceException] captures not only the class and identity of the exception object itself,
 * but also its message and its full stack trace.
 * Stack trace elements are stored as plain `String`s.
 */
@ConsistentCopyVisibility
data class TraceExceptionSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val message: TraceValue,
    val stackTrace: List<String>,
) : TraceReferenceLike() {
    init {
        require(message is TraceNull || message is TraceString || message is TraceRedacted) {
            "Exception message must be null, a captured string, or redacted"
        }
    }

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity" +
            if (message is TraceNull) "" else "($message)"
}

/**
 * Captures a live JVM [Throwable] with its message and full stack trace.
 *
 * As a side-effect, calling [Throwable.getStackTrace] materialises the lazy native `backtrace` on first call;
 * subsequent calls return the cached array.
 * Should be invoked from an ignored section.
 */
fun TraceExceptionSnapshot(context: TraceContext, throwable: Throwable): TraceExceptionSnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(throwable.javaClass.name)
    val message = runCatching { throwable.message }.getOrNull()
        ?.let { TraceString(it, truncate = true) }
        ?: TraceNull
    val stackTrace = runCatching {
        throwable.stackTrace?.map { it.toString() } ?: emptyList()
    }.getOrElse { emptyList() }
    return TraceExceptionSnapshot(classDescriptor, System.identityHashCode(throwable).toLong(), message, stackTrace)
}


// ======== TraceMapSnapshot ========

/**
 * Represents a snapshot of a traced key-value container captured at runtime.
 *
 * Entries are a *list* of pairs, not a [Map]: keys keep their capture order,
 * and two distinct runtime keys that happen to compare equal as [TraceValue]s stay separate rows.
 *
 * A key is a full [TraceValue], so a container keyed by anything other than a string is representable.
 *
 * @property totalSize the container's real entry count, before any capture limit,
 *   so a client can show how many entries it is not seeing.
 */
@ConsistentCopyVisibility
data class TraceMapSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val totalSize: Int,
    val capturedEntries: List<Pair<TraceValue, TraceValue>>,
) : TraceReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}


// ======== TraceTextSnapshot ========

/**
 * A traced [CharSequence] object captured by **both** its identity and a snapshot of its current textual content.
 *
 * Covers [StringBuilder], [StringBuffer], [java.nio.CharBuffer],
 * and other whitelisted [CharSequence] implementations whose `toString()` is safe to call from traced code.
 *
 * Identity is kept because these values are mutable —
 * the captured `content` is a snapshot at capturing time, not a stable property of the source object.
 */
@ConsistentCopyVisibility
data class TraceTextSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val content: String,
) : TraceReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity" + "(\"" + content.escape() + "\")"
}

/**
 * Captures a live JVM [CharSequence] with a snapshot of its textual content.
 *
 * Reading the content calls `toString()`, which is only safe for whitelisted stdlib classes;
 * for any other class, or when `toString()` throws, the content is a fixed placeholder
 * and only the class descriptor and identity carry information.
 */
fun TraceTextSnapshot(context: TraceContext, charSequence: CharSequence, truncate: Boolean = true): TraceTextSnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(charSequence.javaClass.name)
    val content = capturedCharSequenceContent(charSequence, truncate)
    return TraceTextSnapshot(classDescriptor, System.identityHashCode(charSequence).toLong(), content)
}

/** Safely obtains the exact bounded CharSequence content that capture would store. */
internal fun capturedCharSequenceContent(charSequence: CharSequence, truncate: Boolean = true): String {
    // Calling `toString()` on an arbitrary user CharSequence is unsafe, so we guard by Java/Kotlin stdlib packages
    // and additionally wrap in `runCatching` because some implementations may throw
    // if invoked at the "wrong" moment (e.g., a destroyed Segment).
    return if (isClassNameWhitelisted(charSequence)) {
        runCatching {
            charSequence.toString().let { if (truncate) it.truncateForCapture() else it }
        }
        .getOrElse { TRCHAR_SEQUENCE_PLACEHOLDER }
    } else {
        TRCHAR_SEQUENCE_PLACEHOLDER
    }
}

private fun isClassNameWhitelisted(obj: Any): Boolean {
    val className = obj.javaClass.name
    return WHITELIST_PACKAGES_FOR_TO_STRING.any { className.startsWith(it) }
}

private val WHITELIST_PACKAGES_FOR_TO_STRING = listOf(
    // StringBuffer, StringBuilder
    "java.lang.",
    // CharBuffer
    "java.nio.",
    // Segment
    "javax.swing.",
    // Kotlin "wrappers"
    "kotlin.text.",
    "kotlin."
)

private const val MAX_TRSTRING_LENGTH = 50
private const val TRCHAR_SEQUENCE_PLACEHOLDER = "<char sequence content is unavailable>"


// ======== TraceTypeReference ========

/**
 * Which of a runtime's type-object flavours a [TraceTypeReference] names.
 *
 * A new runtime adds a constant here, not a [TraceValue] subclass.
 */
enum class TypeFlavor {
    /** `java.lang.Class`. */
    JAVA_CLASS,
    /** `kotlin.reflect.KClass`. */
    KOTLIN_CLASS,
}

/**
 * A traced reference to a runtime's type object, carried by the referenced type's name.
 *
 * Captured by content rather than by reference identity: two references naming the same type
 * with the same [flavor] are equal. Context-free — shareable across [TraceContext]s.
 */
@ConsistentCopyVisibility
data class TraceTypeReference internal constructor(
    val referencedClassName: String,
    val flavor: TypeFlavor,
) : TraceValue() {
    override fun toString(): String = when (flavor) {
        TypeFlavor.JAVA_CLASS -> "$referencedClassName.class"
        TypeFlavor.KOTLIN_CLASS -> "$referencedClassName.kclass"
    }
}

fun TraceTypeReference(clazz: Class<*>): TraceTypeReference =
    TraceTypeReference(clazz.name, TypeFlavor.JAVA_CLASS)

/**
 * Builds a [TypeFlavor.KOTLIN_CLASS] reference from a `KClass` instance.
 *
 * Takes [Any] because the traced application's `KClass` may be loaded by a different
 * class loader than the javaagent's; guard call sites with `isKClass`.
 */
fun TraceKotlinTypeReference(kClass: Any): TraceTypeReference =
    TraceTypeReference(kClass.kClassReferencedName, TypeFlavor.KOTLIN_CLASS)


// ======== TraceMarker: TraceUnfinishedMethodResult, TraceUntrackedMethodResult ========

/**
 * A sealed parent class for synthetic recorder-emitted markers.
 * These values correspond to no value in the traced program;
 * they mark special situations encountered during tracing,
 * such as the tracer being unable to capture a real value.
 *
 * Distinct from [TraceNull], [TraceVoid], and [TraceUnit], which represent real language-level concepts.
 */
sealed class TraceMarker : TraceValue()

/**
 * Marker denoting that method tracing was cut off before the method returned.
 */
data object TraceUnfinishedMethodResult : TraceMarker() {
    override fun toString(): String = UNFINISHED_METHOD_RESULT_SYMBOL
}

/**
 * Marker denoting the result of an untracked method.
 */
data object TraceUntrackedMethodResult : TraceMarker() {
    override fun toString(): String = UNTRACKED_METHOD_RESULT_SYMBOL
}

const val UNFINISHED_METHOD_RESULT_SYMBOL = "<unfinished method>"
const val UNTRACKED_METHOD_RESULT_SYMBOL = "<untracked result>"
