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
import java.lang.invoke.VarHandle
import org.objectweb.asm.Type
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE

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

internal fun isAtomic(receiver: Any?) =
    // java.util.concurrent
    receiver is AtomicReference<*> ||
    receiver is AtomicBoolean ||
    receiver is AtomicInteger ||
    receiver is AtomicLong ||
    // kotlinx.atomicfu
    receiver is kotlinx.atomicfu.AtomicRef<*> ||
    receiver is kotlinx.atomicfu.AtomicBoolean ||
    receiver is kotlinx.atomicfu.AtomicInt ||
    receiver is kotlinx.atomicfu.AtomicLong

internal fun isAtomicClass(className: String) =
    // java.util.concurrent
    className == "java.util.concurrent.atomic.AtomicInteger" ||
    className == "java.util.concurrent.atomic.AtomicLong" ||
    className == "java.util.concurrent.atomic.AtomicBoolean" ||
    className == "java.util.concurrent.atomic.AtomicReference" ||
    // kotlinx.atomicfu
    className == "kotlinx.atomicfu.AtomicRef" ||
    className == "kotlinx.atomicfu.AtomicBoolean" ||
    className == "kotlinx.atomicfu.AtomicInt" ||
    className == "kotlinx.atomicfu.AtomicLong"


internal fun isAtomicMethod(className: String, methodName: String) =
    isAtomicClass(className) && methodName in atomicMethods

internal fun isAtomicArray(receiver: Any?) =
    // java.util.concurrent
    receiver is AtomicReferenceArray<*> ||
    receiver is AtomicIntegerArray ||
    receiver is AtomicLongArray ||
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

internal fun getAtomicType(atomic: Any?): Type? = when (atomic) {
    is AtomicReference<*>       -> OBJECT_TYPE
    is AtomicBoolean            -> Type.BOOLEAN_TYPE
    is AtomicInteger            -> Type.INT_TYPE
    is AtomicLong               -> Type.LONG_TYPE
    is AtomicReferenceArray<*>  -> OBJECT_TYPE
    is AtomicIntegerArray       -> Type.INT_TYPE
    is AtomicLongArray          -> Type.LONG_TYPE
    else                        -> null
}

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

internal fun isVarHandle(obj: Any?) =
    obj is VarHandle

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

internal fun parseUnsafeMethodAccessType(methodName: String): Type? = when {
    "Boolean"   in methodName -> Type.BOOLEAN_TYPE
    "Byte"      in methodName -> Type.BYTE_TYPE
    "Short"     in methodName -> Type.SHORT_TYPE
    "Int"       in methodName -> Type.INT_TYPE
    "Long"      in methodName -> Type.LONG_TYPE
    "Float"     in methodName -> Type.FLOAT_TYPE
    "Double"    in methodName -> Type.DOUBLE_TYPE
    "Reference" in methodName -> OBJECT_TYPE
    "Object"    in methodName -> OBJECT_TYPE
    else                      -> null
}

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