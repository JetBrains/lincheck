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

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap

/**
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
abstract class BaseMethodHandleLookupRepresentationTest(
    outputFileName: String
) : BaseRunConcurrentRepresentationTest<Unit>(outputFileName) {

    override fun block() {
        // ensure `ConcurrentHashMap` is instrumented before any lookup operations
        ensureConcurrentHashMapIsInstrumented()

        // execute the specific test logic
        doTest()

        // trigger trace collection
        check(false)
    }

    // Abstract method to be implemented by specific test cases
    protected abstract fun doTest()

    private fun ensureConcurrentHashMapIsInstrumented() {
        // creating an instance of `ConcurrentHashMap` will trigger instrumentation of this class
        ConcurrentHashMap<String, String>()
    }
}

class MethodHandlesFindConstructorRepresentationTest : BaseMethodHandleLookupRepresentationTest(
    "method_handles/find_constructor"
) {
    override fun doTest() {
        val constructorHandle = MethodHandles.lookup()
            .findConstructor(Counter::class.java, 
                MethodType.methodType(Void.TYPE, Int::class.java)
            )
        val counter = constructorHandle.invoke(42) as Counter
        check(counter.value == 42)
    }
}


class MethodHandlesFindVirtualRepresentationTest : BaseMethodHandleLookupRepresentationTest(
    "method_handles/find_virtual"
) {
    override fun doTest() {
        val counter = Counter.create()
        val methodHandle = MethodHandles.lookup()
            .findVirtual(Counter::class.java, "increment", 
                MethodType.methodType(Void.TYPE)
            )
        methodHandle.invoke(counter)
        methodHandle.invokeExact(counter)
        check(counter.value == 2)
    }
}

class MethodHandlesFindStaticRepresentationTest : BaseMethodHandleLookupRepresentationTest(
    "method_handles/find_static"
) {
    override fun doTest() {
        val counter = Counter.create()
        val methodHandle = MethodHandles.lookup()
            .findStatic(Counter::class.java, "increment",
                MethodType.methodType(Void.TYPE, Counter::class.java)
            )
        methodHandle.invoke(counter)
        methodHandle.invokeExact(counter)
        check(counter.value == 2)
    }
}

class MethodHandlesFindGetterSetterRepresentationTest : BaseMethodHandleLookupRepresentationTest(
    "method_handles/find_getter_setter"
) {
    override fun doTest() {
        val counter = Counter.create()
        val getterHandle = MethodHandles.lookup()
            .findGetter(Counter::class.java, "value", Int::class.java)
        val setterHandle = MethodHandles.lookup()
            .findSetter(Counter::class.java, "value", Int::class.java)
        setterHandle.invoke(counter, 42)
        check(getterHandle.invoke(counter) == 42)
        check(counter.value == 42)
    }
}

class MethodHandlesBindToRepresentationTest : BaseMethodHandleLookupRepresentationTest(
    "method_handles/bind_to"
) {
    override fun doTest() {
        val counter = Counter.create()
        val methodHandle = MethodHandles.lookup()
            .findVirtual(Counter::class.java, "increment",
                MethodType.methodType(Void.TYPE)
            )
            .bindTo(counter)
        methodHandle.invoke()
        methodHandle.invokeExact()
        check(counter.value == 2)
    }
}

open class Counter() {
    @Volatile
    @JvmField
    var value = 0

    constructor(value: Int) : this() {
        require(value >= 0) { 
            "Counter must be non-negative" 
        }
        this.value = value 
    }

    open fun increment() {
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

class CounterDerived : Counter() {
    override fun increment() {
        value += 2
    }

    companion object {
        @JvmStatic
        fun create(): CounterDerived =
            CounterDerived().also { shared = it } // make the object shared for it to appear in the trace
    }
}
