/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.verifier

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.IllegalStateException
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

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
    expected: Boolean
) {
    val (scenario, results) = scenarioWithResults(block)
    val verifier = verifierClass.getConstructor(ExecutionScenario::class.java, Class::class.java)
        .newInstance(scenario, testClass)
    val res = verifier.verifyResults(results)
    assert(res == expected)
}

fun scenarioWithResults(
    block: ExecutionBuilder.() -> Unit
): Pair<ExecutionScenario, ExecutionResult> = ExecutionBuilder().apply(block).buildScenarioWithResults()

fun actor(f: KFunction<*>, vararg args: Any?): Actor {
    val method = f.javaMethod
        ?: throw IllegalStateException("The function is a constructor or cannot be represented by a Java Method")
    require(method.exceptionTypes.all { Throwable::class.java.isAssignableFrom(it) }) { "Not all declared exceptions are Throwable" }
    return Actor(
        method = method,
        arguments = args.toList(),
        handledExceptions = (method.exceptionTypes as Array<Class<out Throwable>>).toList()
    )
}

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
            initial.map { it.actor },
            parallelExecution,
            post.map { it.actor }
        ) to ExecutionResult(
            initial.map { it.result },
            parallelResults,
            post.map { it.result }
        )
    }
}
