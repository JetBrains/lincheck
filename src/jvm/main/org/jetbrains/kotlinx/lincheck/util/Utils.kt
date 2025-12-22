/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import kotlin.reflect.KClass

fun Boolean.toInt(): Int = this.compareTo(false)

fun Byte.toBoolean(): Boolean = when (this) {
    0.toByte() -> false
    1.toByte() -> true
    else -> throw IllegalArgumentException("Byte $this is not a Boolean")
}

fun Int.toBoolean() = (this == 1)

infix fun Boolean.implies(other: Boolean): Boolean = 
    !this || other

infix fun Boolean.implies(other: () -> Boolean): Boolean =
    !this || other()

infix fun Boolean.equivalent(other: Boolean): Boolean =
    (this && other) || (!this && !other) 
    
inline fun<T> T.runIf(boolean: Boolean, block: T.() -> T): T =
    if (boolean) block() else this

inline fun<reified T> Any?.satisfies(predicate: T.() -> Boolean): Boolean =
    this is T && predicate(this)

inline fun<reified T> Any?.refine(predicate: T.() -> Boolean): T? =
    if (this is T && predicate(this)) this else null


fun <T, R> List<T>.findMapped(transform: (T) -> R?): R? {
    for (element in this) {
        transform(element)?.let { return it }
    }
    return null
}

inline fun Boolean.ensure(): Boolean {
    // TODO: add contract?
    // contract {
    //     returns() implies this
    // }
    check(this)
    return this
}

inline fun<T> T?.ensureNull(lazyMessage: (T?) -> Any): T? {
    check(this == null) { lazyMessage(this) }
    return this
}


inline fun<T> T?.ensureNotNull(lazyMessage: () -> Any): T {
    checkNotNull(this, lazyMessage)
    return this
}


private fun rangeCheck(size: Int, fromIndex: Int, toIndex: Int) {
    when {
        fromIndex > toIndex -> throw IllegalArgumentException("fromIndex ($fromIndex) is greater than toIndex ($toIndex).")
        fromIndex < 0 -> throw IndexOutOfBoundsException("fromIndex ($fromIndex) is less than zero.")
        toIndex > size -> throw IndexOutOfBoundsException("toIndex ($toIndex) is greater than size ($size).")
    }
}

fun<T> List<T>.binarySearch(fromIndex: Int = 0, toIndex: Int = size, predicate: (T) -> Boolean): Int {
    rangeCheck(size, fromIndex, toIndex)
    var low = fromIndex - 1
    var high = toIndex
    while (low + 1 < high) {
        val mid = (low + high).ushr(1) // safe from overflows
        if (predicate(get(mid)))
            high = mid
        else
            low = mid
    }
    return high
}

fun<T> MutableList<T>.expand(size: Int, defaultValue: T) {
    if (size > this.size) {
        addAll(List(size - this.size) { defaultValue })
    }
}

fun<T> MutableList<T>.cut(index: Int) {
    require(index <= size)
    subList(index, size).clear()
}


fun <T> List<T>.squash(relation: (T, T) -> Boolean): List<List<T>> {
    if (isEmpty())
        return emptyList()
    val squashed = arrayListOf<List<T>>()
    var pos = 0
    while (pos < size) {
        val i = pos
        var j = i
        while (++j < size) {
            if (!relation(get(j - 1), get(j)))
                break
        }
        squashed.add(subList(i, j))
        pos = j
    }
    return squashed
}

fun <T> List<Sequence<T>>.cartesianProduct(): Sequence<List<T>> = sequence {
    val sequences = this@cartesianProduct
    if (sequences.isEmpty())
        return@sequence

    // prepare iterators of argument sequences
    val iterators = sequences.map { it.iterator() }
        .toMutableList()
    // compute the first element of each argument sequence,
    // while also count the number of non-empty sequences
    var count = 0
    val elements = iterators.map {
        if (it.hasNext()) it.next().also { count++ } else null
    }.toMutableList()
    // return the empty sequence if at least one of the argument sequences is empty
    if (count != iterators.size)
        return@sequence
    // can cast here since the list can only contain elements
    // returned by iterators' `next()` function
    @Suppress("UNCHECKED_CAST")
    elements as MutableList<T>

    // produce tuples in a loop
    while (true) {
        // yield current tuple (make a copy)
        yield(elements.toMutableList())
        // prepare the next tuple:
        // while the last sequence has elements, spawn it
        if (iterators.last().hasNext()) {
            elements[iterators.lastIndex] = iterators.last().next()
            continue
        }
        // otherwise, reset the last sequence iterator,
        // advance a preceding sequence, and repeat this process
        // until we find a non-exceeded sequence
        var idx = iterators.indices.last
        while (idx >= 0 && !iterators[idx].hasNext()) {
            iterators[idx] = sequences[idx].iterator()
            elements[idx] = iterators[idx].next()
            idx -= 1
        }
        // if all sequences have been exceeded, return
        if (idx < 0)
            return@sequence
        // otherwise, advance the non-exceeded sequence
        elements[idx] = iterators[idx].next()
    }
}

class UnreachableException(message: String?): Exception(message)

fun unreachable(message: String? = null): Nothing {
    throw UnreachableException(message)
}

internal fun Type.getKClass(): KClass<*> = when (sort) {
    Type.INT     -> Int::class
    Type.BYTE    -> Byte::class
    Type.SHORT   -> Short::class
    Type.LONG    -> Long::class
    Type.FLOAT   -> Float::class
    Type.DOUBLE  -> Double::class
    Type.CHAR    -> Char::class
    Type.BOOLEAN -> Boolean::class
    Type.OBJECT  -> when (this) {
        INT_TYPE_BOXED      -> Int::class
        BYTE_TYPE_BOXED     -> Byte::class
        SHORT_TYPE_BOXED    -> Short::class
        LONG_TYPE_BOXED     -> Long::class
        CHAR_TYPE_BOXED     -> Char::class
        BOOLEAN_TYPE_BOXED  -> Boolean::class
        else                -> Any::class
    }
    Type.ARRAY   -> when (elementType.sort) {
        Type.INT     -> IntArray::class
        Type.BYTE    -> ByteArray::class
        Type.SHORT   -> ShortArray::class
        Type.LONG    -> LongArray::class
        Type.FLOAT   -> FloatArray::class
        Type.DOUBLE  -> DoubleArray::class
        Type.CHAR    -> CharArray::class
        Type.BOOLEAN -> BooleanArray::class
        else         -> Array::class
    }
    else -> throw IllegalArgumentException()
}

internal fun KClass<*>.getType(): Type = when (this) {
    Int::class      -> INT_TYPE
    Byte::class     -> BYTE_TYPE
    Short::class    -> SHORT_TYPE
    Long::class     -> LONG_TYPE
    Float::class    -> FLOAT_TYPE
    Double::class   -> DOUBLE_TYPE
    Char::class     -> CHAR_TYPE
    Boolean::class  -> BOOLEAN_TYPE
    else            -> OBJECT_TYPE
}

internal fun KClass<*>.getArrayElementType(): Type = when (this) {
    IntArray::class     -> INT_TYPE
    ByteArray::class    -> BYTE_TYPE
    ShortArray::class   -> SHORT_TYPE
    LongArray::class    -> LONG_TYPE
    FloatArray::class   -> FLOAT_TYPE
    DoubleArray::class  -> DOUBLE_TYPE
    CharArray::class    -> CHAR_TYPE
    BooleanArray::class -> BOOLEAN_TYPE
    Array::class        -> OBJECT_TYPE
    // TODO: should we handle atomic arrays?

    else                -> throw IllegalArgumentException("Argument is not array")
}

internal val INT_TYPE_BOXED      = Type.getType("Ljava/lang/Integer")
internal val LONG_TYPE_BOXED     = Type.getType("Ljava/lang/Long")
internal val SHORT_TYPE_BOXED    = Type.getType("Ljava/lang/Short")
internal val BYTE_TYPE_BOXED     = Type.getType("Ljava/lang/Byte")
internal val CHAR_TYPE_BOXED     = Type.getType("Ljava/lang/Character")
internal val BOOLEAN_TYPE_BOXED  = Type.getType("Ljava/lang/Boolean")