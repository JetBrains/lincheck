/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.verifier

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.verifier.*

/**
 * Kotlin DSL for defining custom scenarios and corresponding expected results.
 * Useful to test special corner cases.
 *
 * Example:
 * ```
 * verify(CustomTest::class.java, LinearizabilityVerifier::class.java, {
 *   initial {
 *     operation(actor(::offer, 1), ValueResult(true))
 *     operation(actor(::offer, 2), ValueResult(true))
 *   }
 *   parallel {
 *     thread {
 *       operation(actor(::r), ValueResult(2))
 *     }
 *     thread {
 *       operation(actor(::r), ValueResult(1))
 *     }
 *   }
 * }, expected = true)
 * ```
 */

fun verify(
    testClass: Class<*>,
    verifierClass: Class<out Verifier>,
    block: ExecutionBuilder.() -> Unit,
    correct: Boolean
) {
    withLincheckDynamicJavaAgent(InstrumentationMode.STRESS) {
        val (scenario, results) = scenarioWithResults(block)
        val verifier = verifierClass.getConstructor(Class::class.java).newInstance(testClass)
        val res = verifier.verifyResults(scenario, results)
        assert(res == correct)
    }
}

fun scenarioWithResults(
    block: ExecutionBuilder.() -> Unit
): Pair<ExecutionScenario, ExecutionResult> = ExecutionBuilder().apply(block).buildScenarioWithResults()

data class Operation(val actor: Actor, val result: Result)

class ThreadExecution : ArrayList<Operation>() {
    fun operation(actor: Actor, result: Result) {
        add(Operation(actor, result))
    }
}

class ParallelExecution : ArrayList<ThreadExecution>() {
    fun thread(block: ThreadExecution.() -> Unit) {
        add(ThreadExecution().apply(block))
    }
}

class ExecutionBuilder {
    private val initial = mutableListOf<Operation>()
    private var parallel = mutableListOf<MutableList<Operation>>()
    private val post = mutableListOf<Operation>()

    fun initial(block: ThreadExecution.() -> Unit) {
        initial.addAll(ThreadExecution().apply(block))
    }

    fun parallel(block: ParallelExecution.() -> Unit) {
        parallel.addAll(ParallelExecution().apply(block))
    }

    fun post(block: ThreadExecution.() -> Unit) {
        post.addAll(ThreadExecution().apply(block))
    }

    fun buildScenarioWithResults(): Pair<ExecutionScenario, ExecutionResult> {
        val parallelResults = mutableListOf<List<Result>>()
        val parallelExecution = mutableListOf<List<Actor>>()
        parallel.forEach {
            parallelExecution.add(it.map { it.actor })
            parallelResults.add(it.map { it.result })
        }
        return ExecutionScenario(
            initExecution = initial.map { it.actor },
            parallelExecution = parallelExecution,
            postExecution = post.map { it.actor },
            validationFunction = null
        ) to ExecutionResult(
            initial.map { it.result },
            parallelResults.map { it.withEmptyClock(parallelExecution.size) },
            post.map { it.result }
        )
    }
}
