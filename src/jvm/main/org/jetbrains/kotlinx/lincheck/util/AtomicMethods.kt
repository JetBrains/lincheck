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

internal fun getAtomicMethodDescriptor(obj: Any?, className: String, methodName: String): AtomicMethodDescriptor? {
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
    receiver is AtomicReference<*> ||
    receiver is AtomicBoolean ||
    receiver is AtomicInteger ||
    receiver is AtomicLong
    // TODO: handle atomicFUs?

internal fun isAtomicArray(receiver: Any?) =
    receiver is AtomicReferenceArray<*> ||
    receiver is AtomicIntegerArray ||
    receiver is AtomicLongArray
    // TODO: handle atomicFUs?

internal fun isAtomicFieldUpdater(obj: Any?) =
    obj is AtomicReferenceFieldUpdater<*, *> ||
    obj is AtomicIntegerFieldUpdater<*> ||
    obj is AtomicLongFieldUpdater<*>

internal fun isVarHandle(obj: Any?) =
    obj is VarHandle

internal fun isUnsafe(receiver: Any?): Boolean =
    if (receiver != null) isUnsafeClass(receiver::class.java.name) else false

internal fun isUnsafeClass(className: String) =
    className == "sun.misc.Unsafe" ||
    className == "jdk.internal.misc.Unsafe"

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
    "set"           to AtomicMethodDescriptor(GET, PLAIN),
    "setOpaque"     to AtomicMethodDescriptor(SET, OPAQUE),
    "setRelease"    to AtomicMethodDescriptor(GET, RELEASE),
    "setVolatile"   to AtomicMethodDescriptor(GET, VOLATILE),

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