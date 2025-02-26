/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import java.lang.invoke.MethodType
import java.lang.invoke.MethodHandles
import java.util.concurrent.ConcurrentHashMap

/*
 * Tests in this file check that various methods from the `MethodHandle` API work correctly.
 *
 * The expected behavior is that:
 *
 *   - various `invoke` methods are correctly analyzed,
 *     the corresponding invoked methods are tracked,
 *     and events from these methods appear in the trace;
 *
 *   - various `Lookup` methods are ignored,
 *     they do not interfere with the analysis,
 *     and events from these methods do not appear in the trace.
 *
 * All the tests follow the same structure.
 *
 *   1. Test enforces instrumentation of the `ConcurrentHashMap` class.
 *      Internally, methods from `MethodHandles.Lookup` use the concurrent hash map
 *      to resolve method names to actual method handles.
 *      If the `ConcurrentHashMap` class is instrumented, but lookup methods are not ignored,
 *      events from the `ConcurrentHashMap` class can appear in the trace.
 *
 *   2. Test lookups a `MethodHandle` (or `VarHandle`) corresponding to some method and invokes it.
 *
 *   3. Test checks that the method was indeed invoked (its side effects are observable).
 *
 *   4. Test triggers the trace collection and checks that no events from lookup methods appear in the trace.
 */

class MethodHandlesFindVirtualRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "method_handles/find_virtual.txt"
) {
    override fun block() {
        ensureConcurrentHashMapIsInstrumented()

        val counter = Counter.create()
        val methodHandle = MethodHandles.lookup()
            .findVirtual(Counter::class.java, "increment", MethodType.methodType(Void.TYPE))
        methodHandle.invoke(counter)
        methodHandle.invokeExact(counter)
        check(counter.value == 2)

        check(false) // to trigger the trace collection
    }
}

class MethodHandlesFindStaticRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "method_handles/find_static.txt"
) {
    override fun block() {
        ensureConcurrentHashMapIsInstrumented()

        val counter = Counter.create()
        val methodHandle = MethodHandles.lookup()
            .findStatic(Counter::class.java, "increment",
                MethodType.methodType(Void.TYPE, Counter::class.java)
            )
        methodHandle.invoke(counter)
        methodHandle.invokeExact(counter)
        check(counter.value == 2)

        check(false) // to trigger the trace collection
    }
}

class MethodHandlesVarHandleRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "method_handles/find_var_handle.txt"
) {
    override fun block() {
        ensureConcurrentHashMapIsInstrumented()

        val counter = Counter.create()
        val varHandle = MethodHandles.lookup()
            .findVarHandle(Counter::class.java, "value", Int::class.java)
        varHandle.set(counter, 42)
        check(counter.value == 42)
        check(varHandle.get(counter) == 42)

        check(false) // to trigger the trace collection
    }
}

private fun ensureConcurrentHashMapIsInstrumented() {
    // creating an instance of `ConcurrentHashMap` will trigger instrumentation of this class
    ConcurrentHashMap<String, String>()
}

private class Counter {
    @Volatile
    @JvmField
    var value = 0

    fun increment() {
        value++
    }

    companion object {
        @JvmField
        var shared: Counter? = null

        @JvmStatic
        fun create(): Counter =
            Counter().also { shared = it } // make the object shared for it to appear in the trace

        @JvmStatic
        fun increment(counter: Counter) {
            counter.value++
        }
    }
}