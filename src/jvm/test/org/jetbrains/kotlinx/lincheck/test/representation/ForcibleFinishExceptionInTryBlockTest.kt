/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.representation

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.test.*
import org.junit.*

class ForcibleFinishExceptionInTryBlockTest {
    private val threadsIn = atomic(0)

    @Operation
    fun operation() = try {
        threadsIn.incrementAndGet()
        // Ensure that both threads are in this while upon deadlock,
        // so that finally block will be executed in the second thread.
        while (threadsIn.value == 2);
    } finally {
        threadsIn.decrementAndGet()
    }

    @Test
    fun test() {
        val options = ModelCheckingOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
            .threads(2)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val forcibleFinishExceptionName = ForcibleExecutionFinishException::class.simpleName!!
        check(failure is DeadlockWithDumpFailure) { "$forcibleFinishExceptionName overrode deadlock because of try-finally" }
        val log = StringBuilder().appendFailure(failure).toString()
        check(forcibleFinishExceptionName !in log) {
            "$forcibleFinishExceptionName was logged"
        }
        checkTraceHasNoLincheckEvents(log)
    }
}