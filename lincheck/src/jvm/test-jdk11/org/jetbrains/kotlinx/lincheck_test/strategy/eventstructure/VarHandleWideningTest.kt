/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

private class LongHolder {
    @JvmField @Volatile var value: Long = 0

    companion object {
        val VALUE: VarHandle = MethodHandles.lookup()
            .findVarHandle(LongHolder::class.java, "value", Long::class.javaPrimitiveType)
    }
}

private class IntHolder {
    @JvmField @Volatile var value: Int = 0

    companion object {
        val VALUE: VarHandle = MethodHandles.lookup()
            .findVarHandle(IntHolder::class.java, "value", Int::class.javaPrimitiveType)
    }
}

/**
 * `VarHandle.set`/`get` are signature-polymorphic methods invoked via `invoke`, not `invokeExact`, so the
 * JVM applies ordinary method invocation conversions (JLS 5.3) to the argument, including primitive
 * widening: `VARHANDLE.set(holder, 1)` on a `long`-typed `VarHandle` legally passes a boxed `Integer` that
 * the VM widens to `long` during linkage. The checker's own value tracking used to assume the argument was
 * already exactly the field's declared boxed type and crashed with `ClassCastException` on the narrower one.
 */
class VarHandleWideningTest {

    @Test
    fun testSetLongFieldWithIntArgument() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(1L)), memoryModel) {
                val holder = LongHolder()
                LongHolder.VALUE.set(holder, 1)
                holder.value
            }
        }
    }

    @Test
    fun testSetIntFieldWithShortArgument() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(1)), memoryModel) {
                val holder = IntHolder()
                IntHolder.VALUE.set(holder, 1.toShort())
                holder.value
            }
        }
    }
}
