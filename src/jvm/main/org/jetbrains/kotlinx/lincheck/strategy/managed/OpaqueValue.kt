/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.objectweb.asm.Type
import kotlin.reflect.KClass

/**
 * Auxiliary class to represent values of variables in managed strategies.
 *
 * [ManagedStrategy] can intercept reads and writes from/to shared variables
 * and call special functions in order to model the behavior of shared memory
 * (see [ManagedStrategy.onSharedVariableRead], [ManagedStrategy.onSharedVariableWrite] and others).
 * These functions in turn may return or take as arguments read/written values
 * and store them in some internal data-structures of the managed strategy.
 * Because of the dynamic nature of the managed strategies memory is untyped,
 * and thus values generally are passed and stored as objects of type [Any].
 * One can encounter several potential pitfalls when operating with these values inside managed strategy.
 *
 * 1. Values of primitive and reference types should be handled differently.
 *    For example, primitive values should be compared structurally,
 *    while reference values --- by reference.
 *
 * 2. It is dangerous to call any methods on a value of reference type,
 *    including methods such as [equals], [hashCode], and [toString].
 *    This is because the class of the value is instrumented by [ManagedStrategyTransformer].
 *    Thus calling methods of the value inside the implementation of managed strategy
 *    can in turn lead to interception of read/writes from/to shared memory
 *    and therefore to recursive calls to internal methods of managed strategy and so on.
 *
 * [OpaqueValue] is a wrapper around value of type [Any] that helps to avoid these problems.
 * It provides safe implementations of [equals], [hashCode], [toString] methods that
 * correctly distinguish values of primitive and reference types.
 * It also provides other useful utility functions for working with values inside [ManagedStrategy].
 *
 * TODO: use @JvmInline value class?
 */
class OpaqueValue private constructor(private val value: Any) {

    companion object {
        fun fromAny(value: Any): OpaqueValue =
            OpaqueValue(value)

        fun default(kClass: KClass<*>): OpaqueValue? = kClass.defaultValue()
    }

    fun unwrap(): Any = value

    val isPrimitive: Boolean
        get() = value.isPrimitive()

    operator fun plus(delta: Number): OpaqueValue = when (value) {
        is Int -> (value + delta as Int).opaque()
        is Long -> (value + delta as Long).opaque()
        // TODO: handle other Numeric types?
        else -> throw IllegalStateException()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is OpaqueValue)
            return false
        return if (isPrimitive) {
            other.isPrimitive && this.value == other.value
        } else (this.value === other.value)
    }

    override fun hashCode(): Int =
        System.identityHashCode(value)

    override fun toString(): String =
        if (isPrimitive) value.toString() else value.toOpaqueString()

}

fun Any.opaque(): OpaqueValue =
    OpaqueValue.fromAny(this)

fun OpaqueValue?.isInstanceOf(kClass: KClass<*>) =
    this?.unwrap()?.let { kClass.isInstance(it) } ?: true

fun KClass<*>.defaultValue(): OpaqueValue? = when(this) {
    Int::class      -> 0
    Byte::class     -> 0.toByte()
    Short::class    -> 0.toShort()
    Long::class     -> 0.toLong()
    Float::class    -> 0.toFloat()
    Double::class   -> 0.toDouble()
    Char::class     -> 0.toChar()
    Boolean::class  -> false
    else            -> null
}?.opaque()

fun Any?.toOpaqueString(): String {
    if (this == null)
        return "null"
    val className = this::class.simpleName.orEmpty()
    val objRepr = Integer.toHexString(System.identityHashCode(this))
    return "${className}@${objRepr}"
}

fun Any.isPrimitive(): Boolean =
    (this::class.javaPrimitiveType != null)

typealias ValueID = Long

internal object StaticObject : Any()

// TODO: override `toString` ?
internal const val INVALID_OBJECT_ID = -2L
internal const val STATIC_OBJECT_ID = -1L
internal const val NULL_OBJECT_ID = 0L

internal fun Int.convert(type: Type): Number = when (type.sort) {
    Type.LONG -> toLong()
    Type.INT  -> this
    else      -> throw IllegalArgumentException("Expected Long or Int")
}