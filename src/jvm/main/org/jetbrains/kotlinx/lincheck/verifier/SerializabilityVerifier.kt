/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.verifier

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*

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

    private val ExecutionScenario.converted get() = ExecutionScenario(
        emptyList(), mergeAndFlatten(initExecution, parallelExecution, postExecution), emptyList()
    )

    private val ExecutionResult.converted: ExecutionResult
        get() {
            val parallelResults = mergeAndFlatten(initResults, parallelResults, postResults)
            val threads = parallelResults.size
            val parallelResultsWithClock = parallelResults.map { it.map { r -> ResultWithClock(r, emptyClock(threads)) } }
            return ExecutionResult(emptyList(), parallelResultsWithClock, emptyList())
        }

    private fun <T> mergeAndFlatten(init: List<T>, parallel: List<List<T>>, post: List<T>): List<List<T>> =
        (init + parallel.flatten() + post).map { listOf(it) }
}