/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.scenario

import org.jetbrains.lincheck.*

/**
 * This class represents an execution scenario.
 *
 * Execution scenarios can be run via [Strategy] to produce an [ExecutionResult].
 * The [ExecutionGenerator] class is responsible for generation of random scenarios.
 */
class ExecutionScenario(
    /**
     * The initial sequential part of the execution.
     * It helps to produce different initial states before the parallel part.
     *
     * The initial part is executed in the first thread of the scenario before the parallel part.
     *
     * The initial part should contain only non-suspendable actors;
     * otherwise, the single initial execution thread will suspend with no chance to be resumed.
     */
    val initExecution: List<Actor>,
    /**
     * The parallel part of the execution.
     * It is used to find an interleaving with incorrect behaviour.
     */
    val parallelExecution: List<List<Actor>>,
    /**
     * The post sequential part of the execution.
     * It is commonly used to test that the data structure is in some valid state.
     *
     * The post part is executed in the first thread of the scenario after the parallel part.
     *
     * If this execution scenario contains suspendable actors, the post part should be empty;
     * if not, an actor could resume a previously suspended one from the parallel execution part.
     */
    val postExecution: List<Actor>,
    /**
     * Validation function that will be called after scenario execution.
     * Is `var` as in case of custom scenario validation function is found only after test class scan.
     */
    var validationFunction: Actor?
) {

    /**
     * Number of threads used by this execution.
     */
    val nThreads: Int
        get() = parallelExecution.size

    /**
     * List containing for each thread its list of actors.
     * Init and post parts are placed in the 1st thread.
     */
    val threads: List<List<Actor>> = (0 until nThreads).map { i ->
        if (i == 0)
            initExecution + parallelExecution[i] + postExecution
        else
            parallelExecution[i]
    }

    override fun toString(): String {
        if (isEmpty()) return "Empty scenario"
        return buildString {
           appendExecutionScenario(this@ExecutionScenario)
        }
    }
}

val ExecutionScenario.isParallelPartEmpty
    get() = parallelExecution.all { it.isEmpty() }

/**
 * Checks if there is at least one suspendable actor in the scenario.
 */
val ExecutionScenario.hasSuspendableActors
    get() = (parallelExecution.flatten() + postExecution).any { it.isSuspendable }

/**
 * Checks if there is at least one suspendable actor in the init part of the scenario.
 */
val ExecutionScenario.hasSuspendableActorsInInitPart
    get() = initExecution.any { it.isSuspendable }

/**
 * Checks if there is at least one suspendable actor in the parallel part,
 * and at the same time post part is not empty.
 */
val ExecutionScenario.hasPostPartAndSuspendableActors
    get() = (parallelExecution.any { actors -> actors.any { it.isSuspendable } } && postExecution.isNotEmpty())

/**
 * Checks if the scenario is valid.
 * Valid scenario should meet the following constrains:
 *   - parallel part should not be empty,
 *   - it should not have suspendable actors in the init part,
 *   - if it contains suspendable actors, then post part should be empty.
 */
val ExecutionScenario.isValid: Boolean
    get() = !isParallelPartEmpty && !hasSuspendableActorsInInitPart && !hasPostPartAndSuspendableActors

/**
 * Validates the execution scenario.
 *
 * @throws IllegalArgumentException if scenario is invalid.
 */
fun ExecutionScenario.validate() {
    require(!isParallelPartEmpty) {
        "Scenario has empty parallel part"
    }
    if (hasSuspendableActors) {
        require(!hasSuspendableActorsInInitPart) {
            "Scenario contains suspendable methods in initial part"
        }
        require(!hasPostPartAndSuspendableActors) {
            "Scenario with suspendable methods has non-empty post part"
        }
    }
}

/**
 * Creates an empty execution scenario.
 */
fun emptyScenario() = ExecutionScenario(emptyList(), emptyList(), emptyList(), null)

/**
 * Checks whether the current execution scenario is empty.
 */
fun ExecutionScenario.isEmpty() =
    initExecution.isEmpty() && parallelExecution.isEmpty() && postExecution.isEmpty() && validationFunction == null

/**
 * Creates a copy of the scenario.
 */
fun ExecutionScenario.copy() = ExecutionScenario(
    ArrayList(initExecution),
    parallelExecution.map { ArrayList(it) },
    ArrayList(postExecution),
    validationFunction
)

const val INIT_THREAD_ID = 0
const val POST_THREAD_ID = 0
const val VALIDATION_THREAD_ID = 0