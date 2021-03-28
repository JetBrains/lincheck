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

package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class A {
    private val sharedState: AtomicInteger = AtomicInteger(0)

    fun a() = synchronized(this) {
        sharedState.incrementAndGet()
        //printErr("a(), sharedState = ${sharedState.toString()}")
    }

    fun b() = synchronized(this) {
        sharedState.decrementAndGet()
        //printErr("b(), sharedState = ${sharedState.toString()}")
    }

    override fun equals(other: Any?): Boolean {
        return this.sharedState.get() == (other as A).sharedState.get()
    }

    override fun hashCode(): Int {
        return this.sharedState.get()
    }
}

@StressCTest(iterations = 30, invocationsPerIteration = 10000, actorsBefore = 2, actorsPerThread = 2, actorsAfter = 2, threads = 3, minimizeFailedScenario = false)
class CompareSpeedTest: VerifierState() {
    val state = A()

    override fun extractState(): Any {
        return state
    }

    @Operation
    fun operation1() = state.a()

    @Operation
    fun operation2() = state.b()

    @Test
    fun test() {
        LinChecker.check(CompareSpeedTest::class.java)
    }
}