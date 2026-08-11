package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.util.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import kotlin.reflect.KClass


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
 * ├── TRNull                           captured JVM `null` reference
 * ├── TRVoid                           void method results
 * ├── TRUnit                           captured Kotlin `Unit` singleton
 * │
 * ├── TRValueLike                      content-tracked values (no reference identity)
 * │   ├── TRPrimitive                  the 8 JVM primitives
 * │   ├── TRString                     java.lang.String
 * │   ├── TREnum                       enum constant — anchored to declaring enum class
 * │   └── TRBigNumber                  arbitrary-precision numbers
 * │       ├── TRBigInteger                 java.math.BigInteger
 * │       └── TRBigDecimal                 java.math.BigDecimal
 * │
 * ├── TRReferenceLike                  identity-tracked values — class descriptor + identity hash code
 * │   ├── TRObject                     generic object — class descriptor + identity hash code
 * │   ├── TRObjectSnapshot             generic object — class descriptor + identity hash code + captured fields
 * │   ├── TRArray                      generic array — class descriptor + identity hash code + size
 * │   ├── TRArraySnapshot              generic array — class descriptor + identity hash code + size + captured elements
 * │   ├── TRCharSequence               CharSequence — identity hash code + textual snapshot
 * │   ├── TRException                  Throwable — class descriptor + identity hash code
 * │   └── TRExceptionSnapshot          Throwable — class descriptor + identity hash code + detail message + stack trace frames
 * │
 * ├── TRClassReference                 references to a JVM class object, captured by name
 * │   ├── TRJavaClass                  java.lang.Class
 * │   └── TRKotlinClass                kotlin.reflect.KClass
 * │
 * ├── TRRedacted                       typed capture slot whose sensitive content was discarded
 * │
 * └── TRMarker                         synthetic recorder-emitted markers
 *     ├── TRUnfinishedMethodResult     method tracing cut off before the method returned
 *     └── TRUntrackedMethodResult      method result of untracked method
 * ```
 */
sealed class TRValue

/**
 * The captured class name for this value,
 * or `null` for [TRNull], [TRVoid], [TRUnit], and [TRMarker] sentinels that don't represent a real class.
 *
 * Subclasses that always have a class name (everything except the sentinels) expose it as a non-nullable member;
 * access this extension only when working with the broad [TRValue] type.
 */
val TRValue.className: String? get() = when (this) {
    is TRNull,
    is TRVoid,
    is TRUnit,
    is TRMarker
        -> null

    is TRRedacted       -> capturedClassName
    is TRString         -> className
    is TRJavaClass      -> className
    is TRKotlinClass    -> className
    is TRPrimitive      -> className
    is TREnum           -> className
    is TRBigInteger     -> className
    is TRBigDecimal     -> className
    is TRReferenceLike  -> className
}

/**
 * The captured [ClassDescriptor] for values that carry application-class identity
 * ([TRReferenceLike] subclasses and [TREnum]);
 * `null` for sentinels, primitives, strings, big numbers, class-references, and recorder markers,
 * which need no descriptor on the wire.
 */
val TRValue.classDescriptor: ClassDescriptor? get() = when (this) {
    is TRReferenceLike -> classDescriptor
    is TREnum -> classDescriptor
    else -> null
}

/**
 * The captured [ClassDescriptor] id for values that carry application-class identity
 * ([TRReferenceLike] subclasses and [TREnum]);
 * `null` for sentinels, primitives, strings, big numbers, class-references, and recorder markers,
 * which need no descriptor on the wire.
 *
 * Subclasses that always have a class id expose it as a non-nullable member;
 * access this extension only when working with the broad [TRValue] type.
 */
val TRValue.classId: Int? get() = when (this) {
    is TRReferenceLike -> classId
    is TREnum -> classId
    else -> null
}

/**
 * A factory function that creates a traced value based on an actual application runtime value.
 *
 * The function determines the specific type of the input object and maps it to a specialized `TRValue` subclass,
 * capturing the runtime snapshot of the value.
 *
 * If the [ClassDescriptor] of the given [value] is not already registered in the given trace [context],
 * performs the registration.
 *
 * @param context The tracing context that provides access to metadata pools and class descriptors.
 * @param value The value to be translated into a [TRValue]. Can be null or any other supported type.
 * @return A [TRValue] instance representing the captured result of the input value.
 */
fun TRValue(context: TraceContext, value: Any?): TRValue = when (value) {
    // special values
    null    -> TRNull
    is Unit -> TRUnit
    else if (value === INJECTIONS_VOID_OBJECT) -> TRVoid

    // primitives
    is Boolean  -> TRPrimitive(value)
    is Byte     -> TRPrimitive(value)
    is Short    -> TRPrimitive(value)
    is Int      -> TRPrimitive(value)
    is Long     -> TRPrimitive(value)
    is Float    -> TRPrimitive(value)
    is Double   -> TRPrimitive(value)
    is Char     -> TRPrimitive(value)

    // strings and char sequences
    is String       -> TRString(value, truncate = true)
    is CharSequence -> TRCharSequence(context, value, truncate = true)

    // exceptions
    is Throwable -> TRException(context, value)

    // non-primitive numeric types
    is BigInteger -> TRBigInteger(value)
    is BigDecimal -> TRBigDecimal(value)

    // enum
    is Enum<*> -> TREnum(context, value)

    // class objects
    is Class<*>  -> TRJavaClass(value)
    is KClass<*> -> TRKotlinClass(value)

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
 * The captured JVM `null` value.
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
 * The Kotlin [Unit] singleton, captured as a value.
 *
 * Distinct from [TRVoid]: a JVM `void` is not representable as actual JVM value,
 * whereas Kotlin's [Unit] has a concrete runtime representation.
 *
 * Single instance; freely shareable across [TraceContext]s.
 */
data object TRUnit : TRValue() {
    override fun toString(): String = "Unit"
}

/**
 * A typed redaction marker that contains policy attribution but no captured content.
 *
 * It intentionally carries no length, hash, reference identity, or other value-derived metadata.
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

// TODO: re-check if we can get rid of this and re-use void object from `Injections`
var INJECTIONS_VOID_OBJECT: Any? = null

// ======== TRValueLike ========

/**
 * A sealed parent class for values recorded by their content rather than by reference identity:
 * - JVM primitives,
 * - strings,
 * - arbitrary-precision numbers,
 * - enum constants,
 * - and other rendered immutables.
 *
 * Most subclasses do not need a [ClassDescriptor] because their Kotlin/JVM type
 * already encodes everything the traced value needs;
 * [TREnum] is the exception, carrying the source enum class so downstream
 * can distinguish two enums that happen to share an entry name.
 */
sealed class TRValueLike : TRValue()


// ======== TRPrimitive ========

/**
 * Represents a traced JVM primitive value.
 *
 * The accepted [value] types are the eight JVM primitives in their boxed form:
 * [Byte], [Short], [Int], [Long], [Float], [Double], [Char], and [Boolean].
 * Kotlin's `Unit` is intentionally not accepted here — it has its own dedicated singleton [TRUnit].
 *
 * Context-free — shareable across [TraceContext]s.
 */
data class TRPrimitive(val value: Any) : TRValueLike() {
    val className: String get() = value.javaClass.name

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
 * Represents a traced JVM string value.
 *
 * Context-free — shareable across [TraceContext]s.
 */
data class TRString(val value: String) : TRValueLike() {
    val className: String get() = String::class.java.name

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
 * may instantiate enums bypasses the `Enum<*>` constructor (e.g., via the Objenesis library);
 * leaving the final `name` field as a JVM `null`.
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
 * Creates a traced representation of an enum constant within the provided trace context.
 *
 * @param context The trace context used to register and resolve the class descriptor for the enum.
 * @param value The enum constant to be traced.
 * @return A `TREnum` object representing the enum constant, linked to its class descriptor in the given context.
 */
fun <E : Enum<*>> TREnum(context: TraceContext, value: E): TREnum {
    val classDescriptor = context.createAndRegisterClassDescriptor(value.javaClass.name)
    return TREnum(classDescriptor, value.name)
}


// ======== TRBigNumber: TRBigInteger, TRBigDecimal ========

/**
 * A sealed parent class for traced arbitrary-precision numbers captured by their `toString()` rendering.
 * Subclasses identify the source JVM type ([BigInteger] vs [BigDecimal]);
 * the rendered [value] is identical to what `toString()` produced at runtime.
 *
 * Context-free — instances are constructed directly without a [TraceContext] and are shareable across contexts.
 */
sealed class TRBigNumber : TRValueLike() {
    abstract val value: String
}

/**
 * Represents a traced arbitrary-precision [BigInteger] captured by its `toString()` rendering.
 *
 * Context-free — shareable across [TraceContext]s.
 *
 * @see TRBigNumber
 */
@ConsistentCopyVisibility
data class TRBigInteger internal constructor(override val value: String) : TRBigNumber() {
    val className: String get() = BigInteger::class.java.name

    constructor(value: BigInteger) : this(value.toString())

    override fun toString(): String = value
}

/**
 * Represents a traced arbitrary-precision [BigDecimal] captured by its `toString()` rendering.
 *
 * Context-free — shareable across [TraceContext]s.
 *
 * @see TRBigNumber
 */
@ConsistentCopyVisibility
data class TRBigDecimal internal constructor(override val value: String) : TRBigNumber() {
    val className: String get() = BigDecimal::class.java.name

    constructor(value: BigDecimal) : this(value.toString())

    override fun toString(): String = value
}


// ======== TRReferenceLike ========

/**
 * A sealed parent class for objects tracked by their reference identity,
 * storing [classDescriptor] and [identityHashCode] captured at application runtime.
 *
 * Snapshot variants additionally carry captured content:
 * fields for objects, elements for arrays, content for char-sequence-like values.
 *
 * Subclasses are inherently context-anchored:
 * the [classDescriptor] refers to a real application class registered with the originating [TraceContext].
 */
sealed class TRReferenceLike : TRValue() {
    abstract val classDescriptor: ClassDescriptor
    abstract val identityHashCode: Int

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
    override val identityHashCode: Int,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@" + identityHashCode
}

/**
 * Creates a new instance of [TRObject] using the given tracing [context] and object,
 * capturing its identity hash code.
 *
 * If the [ClassDescriptor] of the given [obj] is not already registered in the given trace [context],
 * performs the registration.
 *
 * @param context The tracing context that provides access to metadata pools and class descriptors.
 * @param obj The object for which the [TRObject] is created, providing its type and identity.
 * @return A new [TRObject] instance associated with the given object's class and identity.
 */
fun TRObject(context: TraceContext, obj: Any): TRObject {
    val classDescriptor = context.createAndRegisterClassDescriptor(obj.javaClass.name)
    return TRObject(classDescriptor, System.identityHashCode(obj))
}


// ======== TRObjectSnapshot ========

/**
 * Represents a snapshot of a traced object captured during runtime.
 * Unlike [TRObject] captures not only the class and identity hash code of the object itself,
 * but also a snapshot of its fields' values at the moment of capturing.
 */
@ConsistentCopyVisibility
data class TRObjectSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identityHashCode: Int,
    val fields: Map<String, TRValue>,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@" + identityHashCode
}

/**
 * A factory function that captures a snapshot of a given object along with its fields,
 * using the provided trace context.
 *
 * If a [ClassDescriptor] of the given [obj] or any of its fields' values is not already registered
 * in the given trace [context], performs the registration.
 *
 * @param context The tracing context that provides access to metadata pools and class descriptors.
 * @param obj The object to be captured.
 * @param fields A map where keys are field names and values are their respective runtime values
 *   that will be converted to [TRValue] instances.
 * @return A [TRObjectSnapshot] instance representing the captured state of the object and its fields.
 */
fun TRObjectSnapshot(context: TraceContext, obj: Any, fields: Map<String, Any?>): TRObjectSnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(obj.javaClass.name)
    val trObjectMap = fields.mapValues { (_, value) -> TRValue(context, value) }
    return TRObjectSnapshot(classDescriptor, System.identityHashCode(obj), trObjectMap)
}


// ======== TRArray ========

/**
 * Represents a traced array object, identified by its reference identity.
 * In addition to array's class and its identity hash code also captures the array's size.
 */
@ConsistentCopyVisibility
data class TRArray internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identityHashCode: Int,
    val totalSize: Int,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@" + identityHashCode
}

/**
 * Constructs a [TRArray] instance that represents a traced array object.
 *
 * @param context The tracing context that provides access to metadata pools and class descriptors.
 * @param array The JVM array object to be represented; must be a valid array type.
 * @return A captured [TRArray] instance.
 * @throws IllegalArgumentException If the provided `array` is not a JVM array.
 */
fun TRArray(context: TraceContext, array: Any): TRArray {
    require(array.javaClass.isArray) {
        "Value of class ${array.javaClass.name} is not a JVM array"
    }
    val classDescriptor = context.createAndRegisterClassDescriptor(array.javaClass.name)
    val size = getArraySize(array)
    return TRArray(classDescriptor, System.identityHashCode(array), size)
}


// ======== TRArraySnapshot ========

/**
 * Represents a snapshot of a traced array captured at runtime.
 * Unlike [TRArray] captures not only the class, identity hash code, and size of the array object itself,
 * but also a snapshot of its elements' values at the moment of capturing.
 */
@ConsistentCopyVisibility
data class TRArraySnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identityHashCode: Int,
    val totalSize: Int,
    val capturedElements: List<TRValue>,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@" + identityHashCode
}

/**
 * A factory function that captures a snapshot of a given array along with its elements,
 * using the provided trace context.
 *
 * If a [ClassDescriptor] of the given [array] or any of its elements' values is not already registered
 * in the given trace [context], performs the registration.
 *
 * @param context The tracing context that provides access to metadata pools and class descriptors.
 * @param array The array to be captured into a [TRArraySnapshot].
 * @param size The size of the array to be captured.
 * @param elements The list of array elements to be included in the snapshot.
 * @return A [TRArraySnapshot] instance representing the captured state of the given array.
 */
fun TRArraySnapshot(context: TraceContext, array: Any, size: Int, elements: List<Any?>): TRArraySnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(array.javaClass.name)
    val elementsAsTRValues = elements.map { value -> TRValue(context, value) }
    return TRArraySnapshot(classDescriptor, System.identityHashCode(array), size, elementsAsTRValues)
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
    override val identityHashCode: Int,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@" + identityHashCode
}

/**
 * Creates a [TRException] capturing the class and identity of the given [throwable].
 *
 * If the [ClassDescriptor] of the given [throwable] is not already registered in the given
 * trace [context], performs the registration.
 *
 * @param context The tracing context that provides access to metadata pools and class descriptors.
 * @param throwable The [Throwable] to be captured.
 * @return A [TRException] capturing the given [throwable].
 */
fun TRException(context: TraceContext, throwable: Throwable): TRException {
    val classDescriptor = context.createAndRegisterClassDescriptor(throwable.javaClass.name)
    return TRException(classDescriptor, System.identityHashCode(throwable))
}


// ======== TRExceptionSnapshot ========

/**
 * Represents a snapshot of a traced exception captured at runtime.
 * Unlike [TRException] captures not only the class and identity hash code of the exception object itself,
 * but also its message and its full stack trace.
 * Stack trace elements are stored as plain `String`s.
 */
@ConsistentCopyVisibility
data class TRExceptionSnapshot internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identityHashCode: Int,
    val message: TRValue,
    val stackTrace: List<String>,
) : TRReferenceLike() {
    init {
        require(message is TRNull || message is TRString || message is TRRedacted) {
            "Exception message must be null, a captured string, or redacted"
        }
    }

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@" + identityHashCode +
            if (message is TRNull) "" else "($message)"
}

/**
 * Creates a [TRExceptionSnapshot] capturing the class, identity, message,
 * and full stack trace of the given [throwable].
 *
 * As a side-effect, calling [Throwable.getStackTrace] materialises the lazy native `backtrace` on first call;
 * subsequent calls return the cached array.
 * Should be invoked from an ignored section.
 *
 * @param context The tracing context that provides access to metadata pools and class descriptors.
 * @param throwable The [Throwable] to be captured.
 * @return A [TRExceptionSnapshot] capturing the given [throwable].
 */
fun TRExceptionSnapshot(context: TraceContext, throwable: Throwable): TRExceptionSnapshot {
    val classDescriptor = context.createAndRegisterClassDescriptor(throwable.javaClass.name)
    val message = runCatching { throwable.message }.getOrNull()
        ?.let { TRString(it, truncate = true) }
        ?: TRNull
    val stackTrace = runCatching {
        throwable.stackTrace?.map { it.toString() } ?: emptyList()
    }.getOrElse { emptyList() }
    return TRExceptionSnapshot(classDescriptor, System.identityHashCode(throwable), message, stackTrace)
}


// ======== TRCharSequence ========

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
data class TRCharSequence internal constructor(
    override val classDescriptor: ClassDescriptor,
    override val identityHashCode: Int,
    val content: String,
) : TRReferenceLike() {

    override fun toString(): String =
        className.adornedClassNameRepresentation() + "@" + identityHashCode + "(\"" + content.escape() + "\")"
}

/**
 * Creates a traced snapshot of a given [CharSequence], capturing its class descriptor, identity hash code,
 * and a snapshot of its textual content.
 *
 * If the original class of [charSequence] is safe for `toString()` calls during tracing,
 * the implementation fetches the textual content, optionally truncates it if [truncate] flag is true,
 * and stores it in the returned [TRCharSequence].
 * Otherwise — for non-whitelisted classes, or if `toString()` throws — the returned [TRCharSequence]
 * is constructed with a fixed placeholder string as its `content`, while preserving the captured
 * class descriptor and identity hash code.
 *
 * @param context The tracing context that provides access to metadata pools and class descriptors.
 * @param charSequence The [CharSequence] to be captured.
 * @param truncate A flag indicating whether the textual content of [charSequence] should be truncated.
 * @return A [TRCharSequence] capturing the given [charSequence].
 */
fun TRCharSequence(context: TraceContext, charSequence: CharSequence, truncate: Boolean = true): TRCharSequence {
    val classDescriptor = context.createAndRegisterClassDescriptor(charSequence.javaClass.name)
    val content = capturedCharSequenceContent(charSequence, truncate)
    return TRCharSequence(classDescriptor, System.identityHashCode(charSequence), content)
}

/** Safely obtains the exact bounded CharSequence content that capture would store. */
internal fun capturedCharSequenceContent(charSequence: CharSequence, truncate: Boolean = true): String {
    // Whitelisted CharSequence (StringBuilder / StringBuffer / CharBuffer / …):
    // captured with identity + a snapshot of the textual content.
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


// ======== TRClassReference: TRJavaClass, TRKotlinClass ========

/**
 * A sealed parent class for traced references to a JVM [Class] or Kotlin [KClass] object, carried by name.
 *
 * Captured by content rather than by reference identity —
 * two [TRJavaClass] / [TRKotlinClass] instances with the same referenced-class name are equal.
 *
 * Context-free — shareable across [TraceContext]s.
 */
sealed class TRClassReference : TRValue()

/**
 * Represents a traced reference to a JVM [Class], captured by its fully qualified name.
 * Instances are equal by [referencedClassName], not by reference identity.
 *
 * Context-free — shareable across [TraceContext]s.
 */
@ConsistentCopyVisibility
data class TRJavaClass internal constructor(val referencedClassName: String) : TRClassReference() {
    val className: String get() = Class::class.java.name

    constructor(clazz: Class<*>) : this(clazz.name)

    override fun toString(): String = "$referencedClassName.class"
}

/**
 * Represents a traced reference to a Kotlin [KClass], captured by its fully qualified name.
 * Instances are equal by [referencedClassName], not by reference identity.
 *
 * Context-free — shareable across [TraceContext]s.
 */
@ConsistentCopyVisibility
data class TRKotlinClass internal constructor(val referencedClassName: String) : TRClassReference() {
    val className: String get() = KClass::class.java.name

    constructor(kClass: KClass<*>) : this(kClass.java.name)

    override fun toString(): String = "$referencedClassName.kclass"
}


// ======== TRMarker: TRUnfinishedMethodResult, TRUntrackedMethodResult ========

/**
 * A sealed parent class for synthetic recorder-emitted markers.
 * These values do not correspond to any actual JVM runtime value;
 * rather, they are used as markers for special situations encountered during tracing,
 * such as when the tracer was not able to capture a real value.
 *
 * Distinct from [TRNull], [TRVoid], and [TRUnit], which represent JVM-language concepts.
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
