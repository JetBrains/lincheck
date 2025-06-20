/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.datastructures.verifier

import org.jetbrains.kotlinx.lincheck.execution.*

/**
 * This verifier checks that the specified results could be happen in serializable execution.
 * It just tries to find any operations sequence which execution produces the same results.
 */
public class SerializabilityVerifier(
    sequentialSpecification: Class<out Any>
) : CachedVerifier() {
    private val linerizabilityVerifier = LinearizabilityVerifier(sequentialSpecification)

    // always ignore clocks
    override fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult) =
        super.verifyResults(scenario, results.withEmptyClocks)

    override fun verifyResultsImpl(scenario: ExecutionScenario, results: ExecutionResult) =
        linerizabilityVerifier.verifyResultsImpl(scenario.converted, results.converted)

    private val ExecutionScenario.converted
        get() = ExecutionScenario(
            emptyList(),
            mergeAndFlatten(initExecution, parallelExecution, postExecution),
            emptyList(),
            validationFunction
        )

    private val ExecutionResult.converted: ExecutionResult
        get() {
            val parallelResults = mergeAndFlatten(initResults, parallelResults, postResults)
            val threads = parallelResults.size
            val parallelResultsWithClock =
                parallelResults.map { it.map { r -> ResultWithClock(r, emptyClock(threads)) } }
            return ExecutionResult(emptyList(), parallelResultsWithClock, emptyList())
        }

    private fun <T> mergeAndFlatten(init: List<T>, parallel: List<List<T>>, post: List<T>): List<List<T>> =
        (init + parallel.flatten() + post).map { listOf(it) }
}