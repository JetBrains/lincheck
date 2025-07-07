/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import java.util.concurrent.atomic.*
import org.jetbrains.kotlinx.lincheck.util.AtomicMethodKind.*
import org.jetbrains.kotlinx.lincheck.util.MemoryOrdering.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleNames
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

internal data class AtomicMethodDescriptor(
    val kind: AtomicMethodKind,
    val ordering: MemoryOrdering,
)

internal enum class AtomicMethodKind {
    GET, SET,
    GET_AND_SET,
    COMPARE_AND_SET,
    WEAK_COMPARE_AND_SET,
    COMPARE_AND_EXCHANGE,
    GET_AND_ADD, ADD_AND_GET,
    GET_AND_INCREMENT, INCREMENT_AND_GET,
    GET_AND_DECREMENT, DECREMENT_AND_GET;
}

internal val AtomicMethodKind.isSetter get() = when (this) {
    SET,
    GET_AND_SET,
    COMPARE_AND_SET,
    WEAK_COMPARE_AND_SET,
    COMPARE_AND_EXCHANGE
         -> true
    else -> false
}

internal val AtomicMethodKind.isCasSetter get() = when (this) {
    COMPARE_AND_SET,
    WEAK_COMPARE_AND_SET,
    COMPARE_AND_EXCHANGE
         -> true
    else -> false
}

internal enum class MemoryOrdering {
    PLAIN, OPAQUE, RELEASE, ACQUIRE, VOLATILE;

    override fun toString(): String = when (this) {
        PLAIN       -> "Plain"
        OPAQUE      -> "Opaque"
        RELEASE     -> "Release"
        ACQUIRE     -> "Acquire"
        VOLATILE    -> "Volatile"
    }
}

internal fun getAtomicMethodDescriptor(obj: Any?, methodName: String): AtomicMethodDescriptor? {
    return when {
        isAtomic(obj)               -> atomicMethods[methodName]
        isAtomicArray(obj)          -> atomicMethods[methodName]
        isAtomicFieldUpdater(obj)   -> atomicFieldUpdaterMethods[methodName]
        isVarHandle(obj)            -> varHandleMethods[methodName]
        isUnsafe(obj)               -> unsafeMethods[methodName]
        else                        -> null
    }
}

internal fun AtomicMethodDescriptor.getAccessedObject(obj: Any, params: Array<Any?>): Any = when {
    (isAtomicFieldUpdater(obj) || isVarHandle(obj) || isUnsafe(obj)) -> params[0]!!
    else -> obj
}

internal fun AtomicMethodDescriptor.getSetValue(obj: Any?, params: Array<Any?>): Any? {
    var argOffset = 0
    // AFU case - the first argument is an accessed object
    if (isAtomicFieldUpdater(obj))
        argOffset += 1
    // VarHandle case
    if (isVarHandle(obj)) {
        val methodType = VarHandleNames.varHandleMethodType(obj, params)
        // non-static field access case - the first argument is an accessed object
        if (methodType !is VarHandleMethodType.StaticVarHandleMethod)
            argOffset += 1
        // array access case - there is an additional element index argument
        if (methodType is VarHandleMethodType.ArrayVarHandleMethod)
            argOffset += 1
    }
    // Unsafe case - the first argument is an accessed object, plus an additional offset argument
    if (isUnsafe(obj))
        argOffset += 2
    // Atomic arrays case - the first argument is element index
    if (isAtomicArray(obj))
        argOffset += 1
    // CAS case - there is an expected value additional argument
    if (kind.isCasSetter)
        argOffset += 1

    return params[argOffset]
}

internal fun isAtomic(receiver: Any?) =
    isAtomicJava(receiver) ||
    isAtomicFU(receiver)

internal fun isAtomicJava(receiver: Any?) =
    // java.util.concurrent
    receiver is AtomicReference<*> ||
    receiver is AtomicBoolean ||
    receiver is AtomicInteger ||
    receiver is AtomicLong

internal fun isAtomicFU(receiver: Any?) =
    // kotlinx.atomicfu
    receiver is kotlinx.atomicfu.AtomicRef<*> ||
    receiver is kotlinx.atomicfu.AtomicBoolean ||
    receiver is kotlinx.atomicfu.AtomicInt ||
    receiver is kotlinx.atomicfu.AtomicLong


internal fun isAtomicClass(className: String) =
    isAtomicJavaClass(className) ||
    isAtomicFUClass(className)

internal fun isAtomicJavaClass(className: String) =
    // java.util.concurrent
    className == "java.util.concurrent.atomic.AtomicInteger" ||
    className == "java.util.concurrent.atomic.AtomicLong" ||
    className == "java.util.concurrent.atomic.AtomicBoolean" ||
    className == "java.util.concurrent.atomic.AtomicReference"

internal fun isAtomicFUClass(className: String) =
    // kotlinx.atomicfu
    className == "kotlinx.atomicfu.AtomicRef" ||
    className == "kotlinx.atomicfu.AtomicBoolean" ||
    className == "kotlinx.atomicfu.AtomicInt" ||
    className == "kotlinx.atomicfu.AtomicLong"

internal fun isAtomicMethod(className: String, methodName: String) =
    isAtomicClass(className) && methodName in atomicMethods

internal fun isAtomicArray(receiver: Any?) =
    isAtomicArrayJava(receiver) ||
    isAtomicFUArray(receiver)

internal fun isAtomicArrayJava(receiver: Any?) =
    // java.util.concurrent
    receiver is AtomicReferenceArray<*> ||
    receiver is AtomicIntegerArray ||
    receiver is AtomicLongArray

internal fun isAtomicFUArray(receiver: Any?) =
    // kotlinx.atomicfu
    receiver is kotlinx.atomicfu.AtomicArray<*> ||
    receiver is kotlinx.atomicfu.AtomicBooleanArray ||
    receiver is kotlinx.atomicfu.AtomicIntArray ||
    receiver is kotlinx.atomicfu.AtomicLongArray

internal fun isAtomicArrayClass(className: String) =
    // java.util.concurrent
    className == "java.util.concurrent.atomic.AtomicReferenceArray" ||
    className == "java.util.concurrent.atomic.AtomicIntegerArray" ||
    className == "java.util.concurrent.atomic.AtomicLongArray" ||
    // kotlinx.atomicfu
    className == "kotlinx.atomicfu.AtomicArray" ||
    className == "kotlinx.atomicfu.AtomicBooleanArray" ||
    className == "kotlinx.atomicfu.AtomicIntArray" ||
    className == "kotlinx.atomicfu.AtomicLongArray"

internal fun isAtomicArrayMethod(className: String, methodName: String) =
    isAtomicArrayClass(className) && methodName in atomicMethods

internal fun isAtomicFieldUpdater(obj: Any?) =
    obj is AtomicReferenceFieldUpdater<*, *> ||
    obj is AtomicIntegerFieldUpdater<*> ||
    obj is AtomicLongFieldUpdater<*>

internal fun isAtomicFieldUpdaterClass(className: String) =
    className == "java.util.concurrent.atomic.AtomicReferenceFieldUpdater" ||
    className == "java.util.concurrent.atomic.AtomicIntegerFieldUpdater" ||
    className == "java.util.concurrent.atomic.AtomicLongFieldUpdater"

internal fun isAtomicFieldUpdaterMethod(className: String, methodName: String) =
    isAtomicFieldUpdaterClass(className) && methodName in atomicFieldUpdaterMethods

private val varHandleClassNames = setOf(
    "java.lang.invoke.VarHandle",
    "java.lang.invoke.IndirectVarHandle",
    "java.lang.invoke.VarHandleSegmentViewBase",
    "java.lang.invoke.VarHandleSegmentAsBytes",
    "java.lang.invoke.VarHandleSegmentAsChars",
    "java.lang.invoke.VarHandleSegmentAsDoubles",
    "java.lang.invoke.VarHandleSegmentAsFloats",
    "java.lang.invoke.VarHandleSegmentAsInts",
    "java.lang.invoke.VarHandleSegmentAsLongs",
    "java.lang.invoke.VarHandleSegmentAsShorts",
    "java.lang.invoke.VarHandleByteArrayAsChars${'$'}ByteArrayViewVarHandle",
    "java.lang.invoke.VarHandleByteArrayAsChars${'$'}ArrayHandle",
    "java.lang.invoke.VarHandleByteArrayAsChars${'$'}ByteBufferHandle",
    "java.lang.invoke.VarHandleByteArrayAsDoubles${'$'}ByteArrayViewVarHandle",
    "java.lang.invoke.VarHandleByteArrayAsDoubles${'$'}ArrayHandle",
    "java.lang.invoke.VarHandleByteArrayAsDoubles${'$'}ByteBufferHandle",
    "java.lang.invoke.VarHandleByteArrayAsFloats${'$'}ByteArrayViewVarHandle",
    "java.lang.invoke.VarHandleByteArrayAsFloats${'$'}ArrayHandle",
    "java.lang.invoke.VarHandleByteArrayAsFloats${'$'}ByteBufferHandle",
    "java.lang.invoke.VarHandleByteArrayAsInts${'$'}ByteArrayViewVarHandle",
    "java.lang.invoke.VarHandleByteArrayAsInts${'$'}ArrayHandle",
    "java.lang.invoke.VarHandleByteArrayAsInts${'$'}ByteBufferHandle",
    "java.lang.invoke.VarHandleByteArrayAsLongs${'$'}ByteArrayViewVarHandle",
    "java.lang.invoke.VarHandleByteArrayAsLongs${'$'}ArrayHandle",
    "java.lang.invoke.VarHandleByteArrayAsLongs${'$'}ByteBufferHandle",
    "java.lang.invoke.VarHandleByteArrayAsShorts${'$'}ByteArrayViewVarHandle",
    "java.lang.invoke.VarHandleByteArrayAsShorts${'$'}ArrayHandle",
    "java.lang.invoke.VarHandleByteArrayAsShorts${'$'}ByteBufferHandle",
    "java.lang.invoke.VarHandleBooleans${'$'}Array",
    "java.lang.invoke.VarHandleBooleans${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleBooleans${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleBooleans${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleBooleans${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleBytes${'$'}Array",
    "java.lang.invoke.VarHandleBytes${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleBytes${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleBytes${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleBytes${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleChars${'$'}Array",
    "java.lang.invoke.VarHandleChars${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleChars${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleChars${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleChars${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleDoubles${'$'}Array",
    "java.lang.invoke.VarHandleDoubles${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleDoubles${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleDoubles${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleDoubles${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleFloats${'$'}Array",
    "java.lang.invoke.VarHandleFloats${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleFloats${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleFloats${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleFloats${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleInts${'$'}Array",
    "java.lang.invoke.VarHandleInts${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleInts${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleInts${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleInts${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleLongs${'$'}Array",
    "java.lang.invoke.VarHandleLongs${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleLongs${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleLongs${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleLongs${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleReferences${'$'}Array",
    "java.lang.invoke.VarHandleReferences${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleReferences${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleReferences${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleReferences${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleObjects${'$'}Array",
    "java.lang.invoke.VarHandleObjects${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleObjects${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleObjects${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleObjects${'$'}FieldStaticReadWrite",
    "java.lang.invoke.VarHandleShorts${'$'}Array",
    "java.lang.invoke.VarHandleShorts${'$'}FieldInstanceReadOnly",
    "java.lang.invoke.VarHandleShorts${'$'}FieldInstanceReadWrite",
    "java.lang.invoke.VarHandleShorts${'$'}FieldStaticReadOnly",
    "java.lang.invoke.VarHandleShorts${'$'}FieldStaticReadWrite",
)

@OptIn(ExperimentalContracts::class)
internal fun isVarHandle(obj: Any?): Boolean {
    contract { returns(true) implies (obj != null) }
    return obj != null && obj::class.java.name in varHandleClassNames
}

internal fun isVarHandleClass(className: String) =
    className == "java.lang.invoke.VarHandle"

internal fun isVarHandleMethod(className: String, methodName: String) =
    isVarHandleClass(className) && methodName in varHandleMethods

internal fun isUnsafe(receiver: Any?): Boolean =
    if (receiver != null) isUnsafeClass(receiver::class.java.name) else false

internal fun isUnsafeClass(className: String) =
    className == "sun.misc.Unsafe" ||
    className == "jdk.internal.misc.Unsafe"

internal fun isUnsafeMethod(className: String, methodName: String) =
    isUnsafeClass(className) && methodName in unsafeMethods

private val atomicMethods = mapOf(
    // get
    "get"           to AtomicMethodDescriptor(GET, VOLATILE),
    "getAcquire"    to AtomicMethodDescriptor(GET, ACQUIRE),
    "getOpaque"     to AtomicMethodDescriptor(GET, OPAQUE),
    "getPlain"      to AtomicMethodDescriptor(GET, PLAIN),

    // set
    "set"           to AtomicMethodDescriptor(SET, VOLATILE),
    "lazySet"       to AtomicMethodDescriptor(SET, RELEASE),
    "setRelease"    to AtomicMethodDescriptor(SET, RELEASE),
    "setOpaque"     to AtomicMethodDescriptor(SET, OPAQUE),
    "setPlain"      to AtomicMethodDescriptor(SET, PLAIN),

    // getAndSet
    "getAndSet" to AtomicMethodDescriptor(GET_AND_SET, VOLATILE),

    // compareAndSet
    "compareAndSet" to AtomicMethodDescriptor(COMPARE_AND_SET, VOLATILE),

    // weakCompareAndSet
    "weakCompareAndSetVolatile" to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, VOLATILE),
    "weakCompareAndSetAcquire"  to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, ACQUIRE),
    "weakCompareAndSetRelease"  to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, RELEASE),
    "weakCompareAndSetPlain"    to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, PLAIN),
    "weakCompareAndSet"         to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, PLAIN),

    // increments
    "getAndAdd"         to AtomicMethodDescriptor(GET_AND_ADD, VOLATILE),
    "addAndGet"         to AtomicMethodDescriptor(ADD_AND_GET, VOLATILE),
    "getAndIncrement"   to AtomicMethodDescriptor(GET_AND_INCREMENT, VOLATILE),
    "incrementAndGet"   to AtomicMethodDescriptor(INCREMENT_AND_GET, VOLATILE),
    "getAndDecrement"   to AtomicMethodDescriptor(GET_AND_DECREMENT, VOLATILE),
    "decrementAndGet"   to AtomicMethodDescriptor(DECREMENT_AND_GET, VOLATILE),
)

private val atomicFieldUpdaterMethods = mapOf(
    "get"               to AtomicMethodDescriptor(GET, VOLATILE),
    "set"               to AtomicMethodDescriptor(SET, VOLATILE),
    "lazySet"           to AtomicMethodDescriptor(SET, RELEASE),
    "getAndSet"         to AtomicMethodDescriptor(GET_AND_SET, VOLATILE),
    "compareAndSet"     to AtomicMethodDescriptor(COMPARE_AND_SET, VOLATILE),
    "getAndAdd"         to AtomicMethodDescriptor(GET_AND_ADD, VOLATILE),
    "addAndGet"         to AtomicMethodDescriptor(ADD_AND_GET, VOLATILE),
    "getAndIncrement"   to AtomicMethodDescriptor(GET_AND_INCREMENT, VOLATILE),
    "incrementAndGet"   to AtomicMethodDescriptor(INCREMENT_AND_GET, VOLATILE),
    "getAndDecrement"   to AtomicMethodDescriptor(GET_AND_DECREMENT, VOLATILE),
    "decrementAndGet"   to AtomicMethodDescriptor(DECREMENT_AND_GET, VOLATILE),

    // It is unclear from the javadoc what is the intended memory ordering,
    // so we assume `Volatile` as the strongest one
    "weakCompareAndSet" to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, VOLATILE),
)

private val varHandleMethods = mapOf(
    // get
    "get"           to AtomicMethodDescriptor(GET, PLAIN),
    "getOpaque"     to AtomicMethodDescriptor(GET, OPAQUE),
    "getAcquire"    to AtomicMethodDescriptor(GET, ACQUIRE),
    "getVolatile"   to AtomicMethodDescriptor(GET, VOLATILE),

    // set
    "set"           to AtomicMethodDescriptor(SET, PLAIN),
    "setOpaque"     to AtomicMethodDescriptor(SET, OPAQUE),
    "setRelease"    to AtomicMethodDescriptor(SET, RELEASE),
    "setVolatile"   to AtomicMethodDescriptor(SET, VOLATILE),

    // getAndSet
    "getAndSet"         to AtomicMethodDescriptor(GET_AND_SET, VOLATILE),
    "getAndSetRelease"  to AtomicMethodDescriptor(GET_AND_SET, RELEASE),
    "getAndSetAcquire"  to AtomicMethodDescriptor(GET_AND_SET, ACQUIRE),

    // compareAndSet
    "compareAndSet"     to AtomicMethodDescriptor(COMPARE_AND_SET, VOLATILE),

    // weakCompareAndSet
    "weakCompareAndSet"         to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, VOLATILE),
    "weakCompareAndSetAcquire"  to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, ACQUIRE),
    "weakCompareAndSetRelease"  to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, RELEASE),
    "weakCompareAndSetPlain"    to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, PLAIN),

    // compareAndExchange
    "compareAndExchange"        to AtomicMethodDescriptor(COMPARE_AND_EXCHANGE, VOLATILE),
    "compareAndExchangeAcquire" to AtomicMethodDescriptor(COMPARE_AND_EXCHANGE, ACQUIRE),
    "compareAndExchangeRelease" to AtomicMethodDescriptor(COMPARE_AND_EXCHANGE, RELEASE),

    // getAndAdd
    "getAndAdd"         to AtomicMethodDescriptor(GET_AND_ADD, VOLATILE),
    "getAndAddAcquire"  to AtomicMethodDescriptor(GET_AND_ADD, ACQUIRE),
    "getAndAddRelease"  to AtomicMethodDescriptor(GET_AND_ADD, RELEASE),
)

private val unsafeMethods: Map<String, AtomicMethodDescriptor> = run {
    val typeNames = listOf(
        "Boolean", "Char", "Byte", "Short", "Int", "Long", "Float", "Double", "Reference", "Object"
    )
    val getAccessModes = listOf(PLAIN, OPAQUE, ACQUIRE, VOLATILE)
    val putAccessModes = listOf(PLAIN, OPAQUE, RELEASE, VOLATILE)
    val getAndSetAccessModes = listOf(RELEASE, ACQUIRE, VOLATILE)
    val weakCasAccessModes = listOf(PLAIN, RELEASE, ACQUIRE, VOLATILE)
    val exchangeAccessModes = listOf(RELEASE, ACQUIRE, VOLATILE)
    val incrementAccessModes = listOf(RELEASE, ACQUIRE, VOLATILE)
    listOf(
        // get
        typeNames.flatMap { typeName -> getAccessModes.map { accessMode ->
            val accessModeRepr = if (accessMode == PLAIN) "" else accessMode.toString()
            "get$typeName$accessModeRepr" to AtomicMethodDescriptor(GET, accessMode)
        }},
        // put
        typeNames.flatMap { typeName -> putAccessModes.map { accessMode ->
            val accessModeRepr = if (accessMode == PLAIN) "" else accessMode.toString()
            "put$typeName$accessModeRepr" to AtomicMethodDescriptor(SET, accessMode)
        }},
        // getAndSet
        typeNames.flatMap { typeName -> getAndSetAccessModes.map { accessMode ->
            val accessModeRepr = if (accessMode == VOLATILE) "" else accessMode.toString()
            "getAndSet$typeName$accessModeRepr" to AtomicMethodDescriptor(GET_AND_SET, accessMode)
        }},
        // compareAndSet
        typeNames.map { typeName ->
            "compareAndSet$typeName" to AtomicMethodDescriptor(COMPARE_AND_SET, VOLATILE)
        },
        // compareAndSwap
        typeNames.map { typeName ->
            "compareAndSwap$typeName" to AtomicMethodDescriptor(COMPARE_AND_SET, VOLATILE)
        },
        // weakCompareAndSet
        typeNames.flatMap { typeName -> weakCasAccessModes.map { accessMode ->
            val accessModeRepr = if (accessMode == VOLATILE) "" else accessMode.toString()
            "weakCompareAndSet$typeName$accessModeRepr" to AtomicMethodDescriptor(WEAK_COMPARE_AND_SET, accessMode)
        }},
        // compareAndExchange
        typeNames.flatMap { typeName -> exchangeAccessModes.map { accessMode ->
            val accessModeRepr = if (accessMode == VOLATILE) "" else accessMode.toString()
            "compareAndExchange$typeName$accessModeRepr" to AtomicMethodDescriptor(COMPARE_AND_EXCHANGE, accessMode)
        }},
        // getAndAdd
        typeNames
            .filter { it != "Reference" && it != "Object" }
            .flatMap { typeName -> incrementAccessModes.map { accessMode ->
                val accessModeRepr = if (accessMode == VOLATILE) "" else accessMode.toString()
                "getAndAdd$typeName$accessModeRepr" to AtomicMethodDescriptor(GET_AND_ADD, accessMode)
            }}
    ).flatten().toMap()
}