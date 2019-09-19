/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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
package org.jetbrains.kotlinx.lincheck.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.verifier.AbstractLTSVerifier
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.jetbrains.kotlinx.lincheck.verifier.VerifierContext
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.PathCostFunction
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativelyRelaxedLinearizabilityContext

/**
 * This verifier checks that the specified results could happen if the testing operations are linearizable.
 * For that, it tries to find a possible sequential execution where transitions do not violate both
 * LTS (see [LTS]) transitions and the happens-before order. Essentially, it tries to execute the next actor
 * in each thread and goes deeper until all actors are executed.
 *
 * This verifier is based on [AbstractLTSVerifier] and caches the already processed results
 * for performance improvement (see [CachedVerifier]).
 */
class LinearizabilityVerifier(
    scenario: ExecutionScenario,
    sequentialSpecification: Class<*>
) : AbstractLTSVerifier<LTS.State>(scenario, sequentialSpecification) {
    private val relaxationFactor = 0
    private val pathCostFunc = PathCostFunction.NON_RELAXED
    override val lts: LTS = LTS(
        sequentialSpecification = sequentialSpecification,
        isQuantitativelyRelaxed = false,
        relaxationFactor = 0
    )

    override fun createInitialContext(results: ExecutionResult): VerifierContext<LTS.State> =
        QuantitativelyRelaxedLinearizabilityContext(scenario, lts.initialState, results, relaxationFactor, pathCostFunc)
}





