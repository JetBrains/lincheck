/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck.strategy.runIteration
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.createStrategy
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.getResultsVerifier
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.getValue
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.scenario
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.withLincheckTestContext
import org.junit.Assert.*
import org.junit.Test

class ExecutionPrintingTest {
    @Test
    fun doesNotCallUserToStringOrIncludeEventIdentity() {
        fun graph(): String {
            val execution = MutableExtendedExecution(MemoryModel.RelaxedJava)
            val init = AtomicThreadEventImpl(InitializationLabel(-1, 0) { error("Must not read memory") }, null)
            val start = AtomicThreadEventImpl(
                ThreadStartLabel(LabelKind.Receive, 0), null, senders = listOf(init), dependencies = listOf(init)
            )
            val actor = Actor(ArgumentFixture::class.java.getMethod("accept", Any::class.java), listOf(HostileArgument()))
            val call = AtomicThreadEventImpl(ActorLabel(SpanLabelKind.Start, 0, actor), start)
            execution.registerThread(-1)
            execution.registerThread(0)
            listOf(init, start, call).forEach(execution::add)
            return execution.toExecutionGraph(MemoryModel.RelaxedJava, TraceContext())
        }
        val first = graph()
        assertTrue(first.contains("accept(HostileArgument@argument1)"))
        // Event IDs keep increasing between graphs; thread positions and safe object names must not change.
        assertEquals(first, graph())
    }

    @Test
    fun failureKeepsItsOwnGraphAfterFurtherExploration() {
        val scenario = scenario {
            parallel {
                thread { actor(Counter::increment) }
                thread { actor(Counter::increment) }
            }
        }
        withLincheckTestContext(InstrumentationMode.EXPERIMENTAL_MODEL_CHECKING) {
            createStrategy(Counter::class.java, MemoryModel.RelaxedJava, scenario).use { strategy ->
                val failure = strategy.runIteration(1000, getResultsVerifier { results ->
                    results.parallelResults.flatten().map { getValue<Int>(it!!) }.toSet().size == 2
                })
                assertTrue(failure is IncorrectResultsFailure)
                val graph = failure!!.executionGraph!!
                assertEquals(strategy.collectExecutionGraph(), graph)
                assertTrue(graph.contains("rf:"))
                strategy.runIteration(1000, getResultsVerifier { true })
                assertEquals(graph, failure.executionGraph)
                assertTrue(failure.toString().endsWith(graph))
            }
        }
    }

    @Test
    fun minimizedFailureContainsOnlyItsOwnActors() {
        val failure = ModelCheckingOptions()
            .useExperimentalModelChecking()
            .memoryModel(MemoryModel.RelaxedJava)
            .iterations(1)
            .threads(2)
            .actorsPerThread(2)
            .actorsBefore(0)
            .actorsAfter(0)
            .verifier(FailingVerifier::class.java)
            .checkImpl(Counter::class.java)!!
        val actorCount = failure.scenario.initExecution.size + failure.scenario.postExecution.size +
            failure.scenario.parallelExecution.sumOf { it.size }
        assertTrue(actorCount < 4)
        assertEquals(actorCount, failure.executionGraph!!.lines().count { "Actor Start increment(" in it })
    }

    class Counter {
        @Volatile private var value = 0

        @Operation
        fun increment(): Int = ++value
    }

    class ArgumentFixture {
        @Suppress("UNUSED_PARAMETER")
        fun accept(value: Any) = Unit
    }

    class HostileArgument {
        override fun toString(): String = error("User toString must not run while printing an execution graph")
    }
}
