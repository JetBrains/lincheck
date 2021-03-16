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

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.test.*

class FirstTest: VerifierState() {
    val state = A()

    override fun extractState(): Any {
        return state
    }

    @Test
    fun test() {
        val testClass = TestClass("FirstTest") { FirstTest() }

        val actorGenerator1 = ActorGenerator(
            function = {
                instance, arguments ->
                (instance as FirstTest).state.a()
            },
            parameterGenerators = listOf()
        )
        val actorGenerator2 = ActorGenerator(
            function = {
                instance, arguments ->
                (instance as FirstTest).state.b()
            },
            parameterGenerators = listOf()
        )
        val actorGenerators: List<ActorGenerator> = listOf(actorGenerator1, actorGenerator2)
        val operationGroups: List<OperationGroup> = listOf()
        val validationFunctions: List<ValidationFunction> = listOf()
        val stateRepresentation: StateRepresentationFunction? = null
        val testStructure = CTestStructure(
            actorGenerators = actorGenerators,
            operationGroups = operationGroups,
            validationFunctions = validationFunctions,
            stateRepresentation = stateRepresentation
        )

        val options = StressOptions().run {
            iterations(30)
            actorsBefore(2)
            threads(3)
            actorsPerThread(2)
            actorsAfter(2)
            minimizeFailedScenario(false)
        }

        LinChecker.check(testClass = testClass, testStructure = testStructure, options = options)
    }

    class A : SynchronizedObject() {
        private var sharedState = false

        fun a() = synchronized(this) {
            sharedState = true
            if (!sharedState) throw AssertionError()
        }

        fun b() = synchronized(this) {
            sharedState = false
            if (!sharedState) throw AssertionError()
        }

        override fun equals(other: Any?): Boolean {
            return this.sharedState == (other as A).sharedState
        }

        override fun hashCode(): Int {
            return this.sharedState.toByte().toInt()
        }
    }
}