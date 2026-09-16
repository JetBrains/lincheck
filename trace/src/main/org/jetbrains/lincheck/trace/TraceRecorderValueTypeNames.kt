package org.jetbrains.lincheck.trace

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass

/**
 * The type name for this value, with the descriptor-less wire kinds spelled by [spellings].
 *
 * A value captured structurally carries the type its producing runtime reported ([TRValue.className]),
 * which reads the same whichever runtime consumes it, so [spellings] is not consulted for those.
 * The kinds that carry no class descriptor — scalars, strings, arbitrary-precision numbers,
 * type references — are identified by their wire kind alone and have no name of their own on the wire.
 *
 * Pass [JvmTypeSpellings] to read a value as the JVM would, which is what bytecode and descriptor
 * logic needs: a foreign class name then fails to match a JVM descriptor rather than matching one by
 * coincidence. A client that decodes another runtime's stream resolves its own table once — from the
 * handshake that names the runtime — and passes it down.
 */
fun TRValue.typeName(spellings: TypeSpellings): String? = when (this) {
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
        -> spellings.of(this)
}

/**
 * How one runtime spells the wire kinds that carry no class descriptor.
 *
 * A `null` member is a kind that runtime has no name for.
 * This module implements the JVM ([JvmTypeSpellings]); a client that decodes a foreign runtime's
 * stream supplies that runtime's table, so no runtime this module does not implement is named here.
 */
interface TypeSpellings {
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

/** The JVM's spellings — the runtime this module implements. */
object JvmTypeSpellings : TypeSpellings {
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
