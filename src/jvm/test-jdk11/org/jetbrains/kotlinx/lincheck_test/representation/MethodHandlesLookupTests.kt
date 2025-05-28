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

// TODO find out why this test output is different on mac for jdk15
class MethodHandlesFindSpecialRepresentationTest : BaseMethodHandleLookupRepresentationTest(
        "method_handles/find_special"
) {
    override fun doTest() {
        val counter = CounterDerived.create()
        val lookup = MethodHandles.privateLookupIn(CounterDerived::class.java, MethodHandles.lookup())
        val methodHandle = lookup
            .findSpecial(Counter::class.java, "increment",
                MethodType.methodType(Void.TYPE),
                CounterDerived::class.java
            )
        counter.increment()
        methodHandle.invoke(counter)
        methodHandle.invokeExact(counter)
        check(counter.value == 4)
    }
}

class MethodHandlesFindVarHandleRepresentationTest : BaseMethodHandleLookupRepresentationTest(
    "method_handles/find_var_handle"
) {
    override fun doTest() {
        val counter = Counter.create()
        val varHandle = MethodHandles.lookup()
            .findVarHandle(Counter::class.java, "value", Int::class.java)
        varHandle.set(counter, 42)
        check(varHandle.get(counter) == 42)
        check(counter.value == 42)
    }
}
