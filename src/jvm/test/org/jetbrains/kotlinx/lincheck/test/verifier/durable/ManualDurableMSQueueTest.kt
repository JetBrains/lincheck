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

package org.jetbrains.kotlinx.lincheck.test.verifier.durable

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.ValueResult
import org.jetbrains.kotlinx.lincheck.VoidResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.HBClock
import org.jetbrains.kotlinx.lincheck.execution.ResultWithClock
import org.jetbrains.kotlinx.lincheck.test.verifier.linearizability.SequentialQueue
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.junit.Assert
import org.junit.Test
import java.lang.reflect.Method


private val PUSH = DurableMSQueueTest::class.java.getMethod("push", Int::class.javaPrimitiveType)
private val POP = DurableMSQueueTest::class.java.getMethod("pop", Int::class.javaPrimitiveType)


private fun actor(method: Method, vararg a: Any?) = Actor(method, a.toMutableList())

class ManualDurableMSQueueTest {
    @Test
    fun test() {
        val verifier = LinearizabilityVerifier(SequentialQueue::class.java)
        val scenario = ExecutionScenario(
            listOf(actor(PUSH, 2), actor(PUSH, 6), actor(POP, 0), actor(PUSH, -6), actor(PUSH, -8)),
            listOf(
                listOf(actor(POP, 1), actor(POP, 1), actor(PUSH, -8), actor(POP, 1), actor(PUSH, 5)),
                listOf(actor(PUSH, 1), actor(PUSH, 4), actor(POP, 2), actor(POP, 2), actor(PUSH, -4))
            ),
            listOf(actor(PUSH, -8), actor(PUSH, -2), actor(POP, 3), actor(PUSH, -8), actor(POP, 3))
        )
        val clocks = listOf(
            listOf(0 to 2, 1 to 5, 2 to 5, 3 to 5, 4 to 5),
            listOf(0 to 0, 0 to 1, 0 to 2, 1 to 3, 1 to 4)
        ).map { threadClocks -> threadClocks.map { HBClock(it.toList().toIntArray()) } }

        val results = ExecutionResult(
            listOf(VoidResult, VoidResult, ValueResult(2), VoidResult, VoidResult),
            listOf(
                listOf(CrashResult, ValueResult(-8), VoidResult, ValueResult(1), VoidResult),
                listOf(VoidResult, VoidResult, CrashResult, ValueResult(-6), VoidResult)
            ).zip(clocks).map { (res, clock) -> res.zip(clock).map { (r, c) -> ResultWithClock(r, c) } },
            listOf(VoidResult, VoidResult, ValueResult(4), VoidResult, ValueResult(-4))
        )
        Assert.assertTrue(verifier.verifyResults(scenario, results))
    }
}
