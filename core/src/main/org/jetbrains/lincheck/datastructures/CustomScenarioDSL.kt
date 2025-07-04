/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.datastructures

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.*
import java.lang.IllegalStateException
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * Creates a custom scenario using the specified DSL as in the following example:
 *
 * ```
 * scenario {
 *   initial {
 *     actor(QueueTest::offer, 1)
 *     actor(QueueTest::offer, 2)
 *   }
 *   parallel {
 *     thread {
 *       actor(QueueTest::poll)
 *     }
 *     thread {
 *       actor(QueueTest::poll)
 *     }
 *   }
 * }
 * ```
 */
fun scenario(block: DSLScenarioBuilder.() -> Unit): ExecutionScenario =
    DSLScenarioBuilder().apply(block).buildScenario()

/**
 * Create an actor with the specified [function][f] and [arguments][args].
 */
internal fun actor(f: KFunction<*>, vararg args: Any?, cancelOnSuspension: Boolean = false): Actor {
    val method = f.javaMethod ?: throw IllegalStateException("The function is a constructor or cannot be represented by a Java Method")
    require(method.exceptionTypes.all { Throwable::class.java.isAssignableFrom(it) }) { "Not all declared exceptions are Throwable" }
    val requiredArgsCount = method.parameters.size - if (f.isSuspend) 1 else 0
    require(requiredArgsCount == args.size) { "Invalid number of the operation ${f.name} parameters: $requiredArgsCount expected, ${args.size} provided." }
    return Actor(
        method = method,
        arguments = args.toList(),
        cancelOnSuspension = cancelOnSuspension
    )
}

@ScenarioDSLMarker
class DSLThreadScenario : ArrayList<Actor>() {
    /**
     * An actor to be executed
     */
    fun actor(f: KFunction<*>, vararg args: Any?) {
        add(org.jetbrains.lincheck.datastructures.actor(f, *args))
    }
}

@ScenarioDSLMarker
class DSLParallelScenario : ArrayList<DSLThreadScenario>() {
    /**
     * Define a sequence of actors to be executed in a separate thread
     */
    fun thread(block: DSLThreadScenario.() -> Unit) {
        add(DSLThreadScenario().apply(block))
    }
}

@ScenarioDSLMarker
class DSLScenarioBuilder {
    private val initial = mutableListOf<Actor>()
    private var parallel = mutableListOf<MutableList<Actor>>()
    private val post = mutableListOf<Actor>()
    private var initialSpecified = false
    private var parallelSpecified = false
    private var postSpecified = false

    /**
     * Specifies the initial part of the scenario.
     */
    fun initial(block: DSLThreadScenario.() -> Unit) {
        require(!initialSpecified) { "Redeclaration of the initial part is prohibited." }
        initialSpecified = true
        initial.addAll(DSLThreadScenario().apply(block))
    }

    /**
     * Specifies the parallel part of the scenario.
     */
    fun parallel(block: DSLParallelScenario.() -> Unit) {
        require(!parallelSpecified) { "Redeclaration of the parallel part is prohibited." }
        parallelSpecified = true
        parallel.addAll(DSLParallelScenario().apply(block))
    }

    /**
     * Specifies the post part of the scenario.
     */
    fun post(block: DSLThreadScenario.() -> Unit) {
        require(!postSpecified) { "Redeclaration of tthe post part is prohibited." }
        postSpecified = true
        post.addAll(DSLThreadScenario().apply(block))
    }

    /**
     * Constructs a new [scenario][ExecutionScenario] according to
     * the specified [initial], [parallel], and [post] parts.
     * As a validation function can be found only after test class scan, we temporarily set it to `null`.
     */
    fun buildScenario() = ExecutionScenario(initial, parallel, post, validationFunction = null)
}

@DslMarker
private annotation class ScenarioDSLMarker
