package org.jetbrains.lincheck.trace

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass

/**
 * The type name for this value in [runtime]'s own language,
 * or `null` when [runtime] has no name for this value's wire kind.
 *
 * A value captured structurally carries the type its producing runtime reported ([TRValue.className]),
 * which reads the same whichever runtime consumes it.
 * The kinds that carry no class descriptor — scalars, strings, arbitrary-precision numbers,
 * type references — are identified by their wire kind alone,
 * so each runtime spells them in its own language and the spelling comes from a per-runtime table.
 *
 * A runtime with no table yields `null` for those kinds, since no other language's name describes them
 * correctly, and because runtimes may be added without a protocol bump an unrecognised one degrades quietly.
 *
 * JVM bytecode and descriptor logic passes [RUNTIME_JVM] whatever produced the value:
 * it needs the JVM reading, and a foreign class name then fails to match a JVM descriptor
 * rather than matching one by coincidence.
 */
fun TRValue.typeName(runtime: String): String? = when (this) {
    // Structurally captured: the producer already named the type.
    is TRReferenceLike,
    is TREnum,
    is TRRedacted
        -> className

    // No value at all, or no type by design.
    is TRNull,
    is TRVoid,
    is TRUnit,
    is TRMarker,
    is TRRenderedValue
        -> null

    // Identified by wire kind: the spelling is the runtime's.
    is TRScalar,
    is TRString,
    is TRArbitraryNumber,
    is TRTypeReference
        -> spellings(runtime)?.of(this)
}

/**
 * How one runtime spells the wire kinds that carry no class descriptor.
 *
 * A `null` member is a kind the runtime has no name for.
 * A runtime is added by implementing this and registering it in [spellings],
 * which leaves the value model itself unchanged.
 */
private interface TypeSpellings {
    val string: String
    val boolean: String
    val byte: String?
    val short: String?
    val char: String?
    val int: String
    val long: String
    val float: String
    val double: String
    val arbitraryInteger: String
    val arbitraryDecimal: String?
    val javaClass: String?
    val kotlinClass: String?
}

private fun spellings(runtime: String): TypeSpellings? = when (runtime) {
    RUNTIME_JVM -> JvmTypeSpellings
    else -> null
}

private fun TypeSpellings.of(value: TRValue): String? = when (value) {
    is TRString -> string
    is TRArbitraryInteger -> arbitraryInteger
    is TRArbitraryDecimal -> arbitraryDecimal

    is TRScalar -> when (value.value) {
        is Boolean -> boolean
        is Byte -> byte
        is Short -> short
        is Char -> char
        is Int -> int
        is Long -> long
        is Float -> float
        is Double -> double
        // TRScalar's constructor rejects anything else, so this is unreachable.
        else -> null
    }

    is TRTypeReference -> when (value.flavor) {
        TypeFlavor.JAVA_CLASS -> javaClass
        TypeFlavor.KOTLIN_CLASS -> kotlinClass
    }

    else -> null
}

private object JvmTypeSpellings : TypeSpellings {
    override val string: String get() = String::class.java.name
    override val boolean: String get() = java.lang.Boolean::class.java.name
    override val byte: String get() = java.lang.Byte::class.java.name
    override val short: String get() = java.lang.Short::class.java.name
    override val char: String get() = Character::class.java.name
    override val int: String get() = Integer::class.java.name
    override val long: String get() = java.lang.Long::class.java.name
    override val float: String get() = java.lang.Float::class.java.name
    override val double: String get() = java.lang.Double::class.java.name
    override val arbitraryInteger: String get() = BigInteger::class.java.name
    override val arbitraryDecimal: String get() = BigDecimal::class.java.name
    override val javaClass: String get() = Class::class.java.name
    override val kotlinClass: String get() = KClass::class.java.name
}
