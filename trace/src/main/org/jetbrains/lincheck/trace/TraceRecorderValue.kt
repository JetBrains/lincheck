package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.util.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID


// ======== TRValue ========

/**
 * Represents a sealed class hierarchy of traced values.
 *
 * Depending on a particular type of traced value,
 * these values carry a snapshot of its state captured at runtime.
 *
 * High-level hierarchy:
 * ```
 * TRValue                              sealed root
 * ├── TRNull                           captured `null` reference
 * ├── TRVoid                           no value — `void` method result
 * ├── TRUnit                           captured `Unit` singleton
 * │
 * ├── TRValueLike                      content-tracked values (no reference identity)
 * │   ├── TRScalar                     scalar in one of the eight scalar encodings
 * │   ├── TRString                     string
 * │   ├── TREnum                       enum constant — anchored to declaring enum class
 * │   └── TRArbitraryNumber            arbitrary-precision numbers
 * │       ├── TRArbitraryInteger       unbounded integer
 * │       └── TRArbitraryDecimal       unbounded decimal
 * │
 * ├── TRReferenceLike                  identity-tracked values — class descriptor + identity
 * │   ├── TRObject                     generic object — class descriptor + identity
 * │   ├── TRObjectSnapshot             generic object — class descriptor + identity + captured fields
 * │   ├── TRArray                      generic array — class descriptor + identity + size
 * │   ├── TRArraySnapshot              generic array — class descriptor + identity + size + captured elements
 * │   ├── TRMapSnapshot                key-value container — class descriptor + identity + size + captured entries
 * │   ├── TRTextSnapshot               text object — identity + textual snapshot
 * │   ├── TRException                  Throwable — class descriptor + identity
 * │   └── TRExceptionSnapshot          Throwable — class descriptor + identity + detail message + stack trace frames
 * │
 * ├── TRTypeReference                  reference to a runtime type object, discriminated by flavour
 * │
 * ├── TRRedacted                       typed capture slot whose sensitive content was discarded
 * │
 * ├── TRRenderedValue                  leaf value that only carries the agent's display string
 * │
 * └── TRMarker                         synthetic recorder-emitted markers
 *     ├── TRUnfinishedMethodResult     method tracing cut off before the method returned
 *     └── TRUntrackedMethodResult      method result of untracked method
 * ```
 */
sealed class TRValue

/**
 * The class name the *producing runtime* reported for this value, or `null` when it reported none.
 *
 * Only values backed by a [ClassDescriptor] carry one: a structurally captured value names its own type.
 * The kinds that carry no descriptor — scalars, strings, arbitrary-precision numbers, type references —
 * are identified by their wire kind alone; [typeName] spells those kinds per runtime.
 *
 * Subclasses that always have a class name expose it as a non-nullable member;
 * access this extension only when working with the broad [TRValue] type.
 */
val TRValue.className: String? get() = when (this) {
    is TRNull,
    is TRMarker,
    is TRVoid,
    is TRUnit,
    is TRRenderedValue,
    is TRScalar,
    is TRString,
    is TRArbitraryNumber,
    is TRTypeReference
        -> null

    is TRRedacted      -> capturedClassName
    is TREnum          -> className
    is TRReferenceLike -> className
}

/**
 * The captured [ClassDescriptor] for values that carry a producing-runtime class identity
 * ([TRReferenceLike] subclasses and [TREnum]);
 * `null` for every other kind, which is identified by its wire kind and needs no descriptor.
 *
 * Also `null` for [TRRedacted]: a redacted value's own [TRRedacted.classDescriptor] is reachable
 * only through the concrete type.
 */
val TRValue.classDescriptor: ClassDescriptor? get() = when (this) {
    is TRReferenceLike -> classDescriptor
    is TREnum -> classDescriptor

    // A redacted value carries its class name inline rather than a pool id,
    // so its descriptor has no id registered in the context and must not be reached through here.
    is TRRedacted,
    is TRNull,
    is TRVoid,
    is TRUnit,
    is TRMarker,
    is TRRenderedValue,
    is TRScalar,
    is TRString,
    is TRArbitraryNumber,
    is TRTypeReference
        -> null
}

/**
 * The captured [ClassDescriptor] id for values that carry a producing-runtime class identity
 * ([TRReferenceLike] subclasses and [TREnum]);
 * `null` for every other kind, which is identified by its wire kind and needs no descriptor.
 *
 * Subclasses that always have a class id expose it as a non-nullable member;
 * access this extension only when working with the broad [TRValue] type.
 */
val TRValue.classId: Int? get() = when (this) {
    is TRReferenceLike -> classId
    is TREnum -> classId

    // `null` keeps a kind out of the writer's descriptor pre-registration,
    // which is correct for every kind that encodes its type inline or has no type at all.
    is TRRedacted,
    is TRNull,
    is TRVoid,
    is TRUnit,
    is TRMarker,
    is TRRenderedValue,
    is TRScalar,
    is TRString,
    is TRArbitraryNumber,
    is TRTypeReference
        -> null
}

/**
 * Captures a live JVM value as the [TRValue] subclass that matches its JVM type.
 *
 * JVM-specific: the dispatch below reads the value's Java class, so it is the JVM agent's entry point
 * into the runtime-neutral value model.
 *
 * Registers the [ClassDescriptor] of [value] in [context] when the value carries class identity
 * and the descriptor is not registered yet.
 */
fun TRValue(context: TraceContext, value: Any?): TRValue = when (value) {
    // special values
    null    -> TRNull
    else if (value.isUnit) -> TRUnit
    else if (value === INJECTIONS_VOID_OBJECT) -> TRVoid

    // primitives
    is Boolean  -> TRScalar(value)
    is Byte     -> TRScalar(value)
    is Short    -> TRScalar(value)
    is Int      -> TRScalar(value)
    is Long     -> TRScalar(value)
    is Float    -> TRScalar(value)
    is Double   -> TRScalar(value)
    is Char     -> TRScalar(value)

    // strings and char sequences
    is String       -> TRString(value, truncate = true)
    is CharSequence -> TRTextSnapshot(context, value, truncate = true)

    // exceptions
    is Throwable -> TRException(context, value)

    // non-primitive numeric types
    is BigInteger -> TRArbitraryInteger(value)
    is BigDecimal -> TRArbitraryDecimal(value)

    // enum
    is Enum<*> -> TREnum(context, value)

    // class objects
    is Class<*>  -> TRTypeReference(value)
    else if (value.isKClass) -> TRKotlinTypeReference(value)

    // arrays
    is Array<*>     -> TRArray(context, value)
    is IntArray     -> TRArray(context, value)
    is LongArray    -> TRArray(context, value)
    is ByteArray    -> TRArray(context, value)
    is ShortArray   -> TRArray(context, value)
    is CharArray    -> TRArray(context, value)
    is FloatArray   -> TRArray(context, value)
    is DoubleArray  -> TRArray(context, value)
    is BooleanArray -> TRArray(context, value)

    // generic object
    else -> TRObject(context, value)
}

// ======== TRNull, TRVoid, TRUnit ========

/**
 * A captured `null` reference.
 *
 * Every trace-point slot that captures a value uses [TRNull] to denote a captured `null` reference.
 * By the same convention, the receiver slot of a static method or static field access is also [TRNull],
 * mirroring the JVM bytecode shape where `invokestatic` / `getstatic` have no `this`.
 *
 * Single instance; freely shareable across [TraceContext]s.
 */
data object TRNull : TRValue() {
    override fun toString(): String = "null"
}

/**
 * The "no value" marker used for void-return method results.
 *
 * Single instance; freely shareable across [TraceContext]s.
 */
data object TRVoid : TRValue() {
    override fun toString(): String = "void"
}

/**
 * A captured absence of value.
 * Distinct from [TRVoid]: A `void` return is not representable as a value, whereas `Unit` is a value that carries nothing.
 *
 * Single instance; freely shareable across [TraceContext]s.
 */
data object TRUnit : TRValue() {
    override fun toString(): String = "Unit"
}

/**
 * A typed redaction marker that contains policy attribution but no captured content.
 *
 * It carries no length, hash, reference identity, or other value-derived metadata,
 * since each of those leaks information about the content.
 */
data class TRRedacted(
    val classDescriptor: ClassDescriptor?,
    val templateUuid: UUID?,
    val templateName: String?,
) : TRValue() {
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
 * An agent that can read the structure sends [TRObjectSnapshot] or [TRArraySnapshot]
 * and registers the type in the [TraceContext] class-descriptor pool,
 * which keeps one value model for every runtime.
 */
data class TRRenderedValue(val rendered: String) : TRValue() {
    override fun toString(): String = rendered
}

// TODO: re-check if we can get rid of this and re-use void object from `Injections`
var INJECTIONS_VOID_OBJECT: Any? = null

// ======== TRValueLike ========

/**
 * A sealed parent class for values recorded by their content, with no reference identity:
 * scalars, strings, arbitrary-precision numbers, and enum constants.
 *
 * Most subclasses need no [ClassDescriptor] because their wire kind already identifies the type;
 * [TREnum] is the exception, carrying the declaring enum class so a consumer
 * can distinguish two enums that happen to share an entry name.
 */
sealed class TRValueLike : TRValue()


// ======== TRScalar ========

/**
 * A traced scalar value — one of the eight scalar encodings the trace model defines:
 * boolean, byte, short, int, long, float, double, char.
 *
 * The model is written in Kotlin, so [value] holds the scalar boxed:
 * [Boolean], [Byte], [Short], [Int], [Long], [Float], [Double], or [Char].
 * `Unit` is not a scalar — it arrives as [TRUnit].
 *
 * Context-free — shareable across [TraceContext]s.
 */
data class TRScalar(val value: Any) : TRValueLike() {
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


// ======== TRString ========

/**
 * A traced string value.
 *
 * Context-free — shareable across [TraceContext]s.
 */
data class TRString(val value: String) : TRValueLike() {
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


// ======== TREnum ========

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
data class TREnum internal constructor(
    val classDescriptor: ClassDescriptor,
    val name: String?,
) : TRValueLike() {
    val classId: Int get() = classDescriptor.id
    val className: String get() = classDescriptor.name

    override fun toString(): String = "${className.getSimpleClassName()}.$name"
}

/**
 * Captures a live JVM enum constant, registering its declaring class in [context] if needed.
 */
fun <E : Enum<*>> TREnum(context: TraceContext, value: E): TREnum {
    val classDescriptor = context.createAndRegisterClassDescriptor(value.javaClass.name)
    return TREnum(classDescriptor, value.name)
}


// ======== TRArbitraryNumber: TRArbitraryInteger, TRArbitraryDecimal ========

/**
 * A sealed parent class for traced arbitrary-precision numbers, captured as their decimal rendering.
 * Subclasses split unbounded integers from unbounded decimals;
 * [value] is the rendering the producing runtime reported.
 *
 * Context-free — instances are constructed directly without a [TraceContext] and are shareable across contexts.
 */
sealed class TRArbitraryNumber : TRValueLike() {
    abstract val value: String
}

/**
 * A traced unbounded integer, held as its decimal rendering.
 * The [BigInteger] constructor is the JVM capture path.
 *
 * Context-free — shareable across [TraceContext]s.
 *
 * @see TRArbitraryNumber
 */
@ConsistentCopyVisibility
data class TRArbitraryInteger internal constructor(override val value: String) : TRArbitraryNumber() {
    constructor(value: BigInteger) : this(value.toString())

    override fun toString(): String = value
}

/**
 * A traced unbounded decimal, held as its decimal rendering.
 * The [BigDecimal] constructor is the JVM capture path.
 *
 * Context-free — shareable across [TraceContext]s.
 *
 * @see TRArbitraryNumber
 */
@ConsistentCopyVisibility
data class TRArbitraryDecimal internal constructor(override val value: String) : TRArbitraryNumber() {
    constructor(value: BigDecimal) : this(value.toString())

    override fun toString(): String = value
}


// ======== TRReferenceLike ========

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
sealed class TRReferenceLike : TRValue() {
    abstract val classDescriptor: ClassDescriptor

    /**
     * A stable per-object identity in the producing runtime.
     */
    abstract val identity: Long

    val classId: Int get() = classDescriptor.id
    val className: String get() = classDescriptor.name

}


// ======== TRObject ========

/**
 * Represents a generic traced runtime object, identified by its reference identity.
 *
 * Objects of this class are tied to a specific tracing context,
 * meaning the [classDescriptor] refers to a class registered within the related [TraceContext].
 *
 * @see TRReferenceLike
 */
@ConsistentCopyVisibility
data class TRObject internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}

/**
 * Captures a live JVM object by its class and `System.identityHashCode`,
 * registering its [ClassDescriptor] in [context] if needed.
 */
fun TRObject(context: TraceContext, obj: Any): TRObject {
    val classDescriptor = context.createAndRegisterClassDescriptor(obj.javaClass.name)
    return TRObject(classDescriptor, System.identityHashCode(obj).toLong())
}


// ======== TRObjectSnapshot ========

/**
 * Represents a snapshot of a traced object captured during runtime.
 * Unlike [TRObject] captures not only the class and identity of the object itself,
 * but also a snapshot of its fields' values at the moment of capturing.
 */
@ConsistentCopyVisibility
data class TRObjectSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val fields: Map<String, TRValue>,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}

/**
 * Captures a live JVM object together with the already-read [fields], keyed by field name.
 *
 * Registers the [ClassDescriptor] of [obj] and of every field value in [context] if needed.
 */
fun TRObjectSnapshot(context: TraceContext, obj: Any, fields: Map<String, Any?>): TRObjectSnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(obj.javaClass.name)
    val trObjectMap = fields.mapValues { (_, value) -> TRValue(context, value) }
    return TRObjectSnapshot(classDescriptor, System.identityHashCode(obj).toLong(), trObjectMap)
}


// ======== TRArray ========

/**
 * Represents a traced array object, identified by its reference identity.
 * In addition to the array's class and identity also captures the array's size.
 */
@ConsistentCopyVisibility
data class TRArray internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val totalSize: Int,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}

/**
 * Captures a live JVM array by its class, `System.identityHashCode`, and length.
 *
 * @throws IllegalArgumentException if [array] is not a JVM array.
 */
fun TRArray(context: TraceContext, array: Any): TRArray {
    require(array.javaClass.isArray) {
        "Value of class ${array.javaClass.name} is not a JVM array"
    }
    val classDescriptor = context.createAndRegisterClassDescriptor(array.javaClass.name)
    val size = getArraySize(array)
    return TRArray(classDescriptor, System.identityHashCode(array).toLong(), size)
}


// ======== TRArraySnapshot ========

/**
 * Represents a snapshot of a traced array captured at runtime.
 * Unlike [TRArray] captures not only the class, identity, and size of the array object itself,
 * but also a snapshot of its elements' values at the moment of capturing.
 */
@ConsistentCopyVisibility
data class TRArraySnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val totalSize: Int,
    val capturedElements: List<TRValue>,
) : TRReferenceLike() {

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
fun TRArraySnapshot(context: TraceContext, array: Any, size: Int, elements: List<Any?>): TRArraySnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(array.javaClass.name)
    val elementsAsTRValues = elements.map { value -> TRValue(context, value) }
    return TRArraySnapshot(classDescriptor, System.identityHashCode(array).toLong(), size, elementsAsTRValues)
}


// ======== TRException ========

/**
 * Represents a traced exception object, identified by its reference identity.
 *
 * @see TRReferenceLike
 * @see TRExceptionSnapshot
 */
@ConsistentCopyVisibility
data class TRException internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}

/**
 * Captures a live JVM [Throwable] by its class and `System.identityHashCode`,
 * registering its [ClassDescriptor] in [context] if needed.
 */
fun TRException(context: TraceContext, throwable: Throwable): TRException {
    val classDescriptor = context.createAndRegisterClassDescriptor(throwable.javaClass.name)
    return TRException(classDescriptor, System.identityHashCode(throwable).toLong())
}


// ======== TRExceptionSnapshot ========

/**
 * Represents a snapshot of a traced exception captured at runtime.
 * Unlike [TRException] captures not only the class and identity of the exception object itself,
 * but also its message and its full stack trace.
 * Stack trace elements are stored as plain `String`s.
 */
@ConsistentCopyVisibility
data class TRExceptionSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val message: TRValue,
    val stackTrace: List<String>,
) : TRReferenceLike() {
    init {
        require(message is TRNull || message is TRString || message is TRRedacted) {
            "Exception message must be null, a captured string, or redacted"
        }
    }

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity" +
            if (message is TRNull) "" else "($message)"
}

/**
 * Captures a live JVM [Throwable] with its message and full stack trace.
 *
 * As a side-effect, calling [Throwable.getStackTrace] materialises the lazy native `backtrace` on first call;
 * subsequent calls return the cached array.
 * Should be invoked from an ignored section.
 */
fun TRExceptionSnapshot(context: TraceContext, throwable: Throwable): TRExceptionSnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(throwable.javaClass.name)
    val message = runCatching { throwable.message }.getOrNull()
        ?.let { TRString(it, truncate = true) }
        ?: TRNull
    val stackTrace = runCatching {
        throwable.stackTrace?.map { it.toString() } ?: emptyList()
    }.getOrElse { emptyList() }
    return TRExceptionSnapshot(classDescriptor, System.identityHashCode(throwable).toLong(), message, stackTrace)
}


// ======== TRMapSnapshot ========

/**
 * Represents a snapshot of a traced key-value container captured at runtime.
 *
 * Entries are a *list* of pairs, not a [Map]: keys keep their capture order,
 * and two distinct runtime keys that happen to compare equal as [TRValue]s stay separate rows.
 *
 * A key is a full [TRValue], so a container keyed by anything other than a string is representable.
 *
 * @property totalSize the container's real entry count, before any capture limit,
 *   so a client can show how many entries it is not seeing.
 */
@ConsistentCopyVisibility
data class TRMapSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val totalSize: Int,
    val capturedEntries: List<Pair<TRValue, TRValue>>,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@$identity"
}


// ======== TRTextSnapshot ========

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
data class TRTextSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identity: Long,
    val content: String,
) : TRReferenceLike() {

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
fun TRTextSnapshot(context: TraceContext, charSequence: CharSequence, truncate: Boolean = true): TRTextSnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(charSequence.javaClass.name)
    val content = capturedCharSequenceContent(charSequence, truncate)
    return TRTextSnapshot(classDescriptor, System.identityHashCode(charSequence).toLong(), content)
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


// ======== TRTypeReference ========

/**
 * Which of a runtime's type-object flavours a [TRTypeReference] names.
 *
 * A new runtime adds a constant here, not a [TRValue] subclass.
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
data class TRTypeReference internal constructor(
    val referencedClassName: String,
    val flavor: TypeFlavor,
) : TRValue() {
    override fun toString(): String = when (flavor) {
        TypeFlavor.JAVA_CLASS -> "$referencedClassName.class"
        TypeFlavor.KOTLIN_CLASS -> "$referencedClassName.kclass"
    }
}

fun TRTypeReference(clazz: Class<*>): TRTypeReference =
    TRTypeReference(clazz.name, TypeFlavor.JAVA_CLASS)

/**
 * Builds a [TypeFlavor.KOTLIN_CLASS] reference from a `KClass` instance.
 *
 * Takes [Any] because the traced application's `KClass` may be loaded by a different
 * class loader than the javaagent's; guard call sites with `isKClass`.
 */
fun TRKotlinTypeReference(kClass: Any): TRTypeReference =
    TRTypeReference(kClass.kClassReferencedName, TypeFlavor.KOTLIN_CLASS)


// ======== TRMarker: TRUnfinishedMethodResult, TRUntrackedMethodResult ========

/**
 * A sealed parent class for synthetic recorder-emitted markers.
 * These values correspond to no value in the traced program;
 * they mark special situations encountered during tracing,
 * such as the tracer being unable to capture a real value.
 *
 * Distinct from [TRNull], [TRVoid], and [TRUnit], which represent real language-level concepts.
 */
sealed class TRMarker : TRValue()

/**
 * Marker denoting that method tracing was cut off before the method returned.
 */
data object TRUnfinishedMethodResult : TRMarker() {
    override fun toString(): String = UNFINISHED_METHOD_RESULT_SYMBOL
}

/**
 * Marker denoting the result of an untracked method.
 */
data object TRUntrackedMethodResult : TRMarker() {
    override fun toString(): String = UNTRACKED_METHOD_RESULT_SYMBOL
}

const val UNFINISHED_METHOD_RESULT_SYMBOL = "<unfinished method>"
const val UNTRACKED_METHOD_RESULT_SYMBOL = "<untracked result>"
