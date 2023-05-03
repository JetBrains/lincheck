/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*

/**
 * This class represents a result corresponding to
 * the specified [scenario][ExecutionScenario] execution.
 *
 * All the result parts should have the same dimensions as the scenario.
 */
data class ExecutionResult(
    /**
     * Results of the initial sequential part of the execution.
     * @see ExecutionScenario.initExecution
     */
    val initResults: List<Result>,
    /**
     * State representation at the end of the init part.
     */
    val afterInitStateRepresentation: String?,
    /**
     * Results of the parallel part of the execution with the clock values at the beginning of each one.
     * @see ExecutionScenario.parallelExecution
     */
    val parallelResultsWithClock: List<List<ResultWithClock>>,
    /**
     * State representation at the end of the parallel part.
     */
    val afterParallelStateRepresentation: String?,
    /**
     * Results of the last sequential part of the execution.
     * @see ExecutionScenario.postExecution
     */
    val postResults: List<Result>,
    /**
     * State representation at the end of the scenario.
     */
    val afterPostStateRepresentation: String?
) {
    constructor(initResults: List<Result>, parallelResultsWithClock: List<List<ResultWithClock>>, postResults: List<Result>) :
        this(initResults, null, parallelResultsWithClock, null, postResults, null)

    /**
     * Override `equals` to ignore states.
     * We do not require state generation to be deterministic, so
     * states can differ for the same interleaving.
     */
    override fun equals(other: Any?): Boolean =
        other is ExecutionResult &&
        initResults == other.initResults &&
        parallelResultsWithClock == other.parallelResultsWithClock &&
        postResults == other.postResults

    override fun hashCode(): Int {
        var result = initResults.hashCode()
        result = 31 * result + parallelResultsWithClock.hashCode()
        result = 31 * result + postResults.hashCode()
        return result
    }
}

val ExecutionResult.withEmptyClocks: ExecutionResult get() = ExecutionResult(
    this.initResults,
    this.afterInitStateRepresentation,
    this.parallelResultsWithClock.map { it.withEmptyClock() },
    this.afterParallelStateRepresentation,
    this.postResults,
    this.afterPostStateRepresentation
)

val ExecutionResult.parallelResults: List<List<Result>> get() = parallelResultsWithClock.map { it.map { r -> r.result } }

// for tests
fun ExecutionResult.equalsIgnoringClocks(other: ExecutionResult) = this.withEmptyClocks == other.withEmptyClocks
