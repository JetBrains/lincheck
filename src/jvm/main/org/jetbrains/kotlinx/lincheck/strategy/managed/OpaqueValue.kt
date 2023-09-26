/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

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
class OpaqueValue private constructor(
    private val value: Any,
    val kClass: KClass<*> = value.javaClass.kotlin,
) {

    companion object {
        fun fromAny(value: Any, kClass: KClass<*> = value.javaClass.kotlin): OpaqueValue =
            OpaqueValue(value, kClass)

        fun default(kClass: KClass<*>): OpaqueValue? = kClass.defaultValue()
    }

    val isPrimitive: Boolean =
        (kClass.javaPrimitiveType != null)

    fun unwrap(): Any = value

    operator fun plus(delta: Number): OpaqueValue = when (value) {
        is Int -> (value + delta as Int).opaque(Int::class)
        is Long -> (value + delta as Long).opaque(Long::class)
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
        if (isPrimitive) value.toString() else opaqueString(value)

}

fun Any.opaque(kClass: KClass<*> = this.javaClass.kotlin): OpaqueValue =
    OpaqueValue.fromAny(this, kClass)

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
}?.opaque(kClass = this)

fun opaqueString(className: String, obj: Any): String =
    "${className}@${Integer.toHexString(System.identityHashCode(obj))}"

// TODO: use obj.opaque().toString() instead?
fun opaqueString(obj: Any?): String =
    if (obj != null) opaqueString(obj::class.simpleName ?: "", obj) else "null"

fun Any.isPrimitive(): Boolean =
    (this::class.javaPrimitiveType != null)

sealed class ValueID

data class PrimitiveID(val value: Any): ValueID() {
    init {
        require(value.isPrimitive())
    }
}

data class ObjectID(val id: Int): ValueID()

// TODO: override `toString` ?
internal val INVALID_OBJECT_ID = ObjectID(-2)
internal val STATIC_OBJECT_ID = ObjectID(-1)
internal val NULL_OBJECT_ID = ObjectID(0)