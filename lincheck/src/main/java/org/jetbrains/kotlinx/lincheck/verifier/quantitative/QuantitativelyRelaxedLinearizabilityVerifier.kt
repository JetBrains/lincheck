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
package org.jetbrains.kotlinx.lincheck.verifier.quantitative

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.verifier.AbstractLTSVerifier
import org.jetbrains.kotlinx.lincheck.verifier.DummySequentialSpecification
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.jetbrains.kotlinx.lincheck.verifier.VerifierContext

/**
 * This verifier checks for quantitative relaxation contracts, which are introduced
 * in the "Quantitative relaxation of concurrent data structures" paper by Henzinger et al.
 *
 * Requires [QuantitativeRelaxationVerifierConf] annotation on the testing class.
 *
 * The cost counter class should be provided as the sequential specification
 * (see {@link Options#sequentialSpecification(Class)}).
 * This class should represent the current data structure state
 * and has the same methods as testing operations,
 * but with an additional {@link Result} parameter
 * and another return type.
 * If an operation is not relaxed this cost counter
 * should check that the operation result is correct
 * and return the next state (which is a cost counter too)
 * or {@code null} in case the result is incorrect.
 * Otherwise, if a corresponding operation is relaxed
 * (annotated with {@link QuantitativeRelaxed}),
 * the method should return a list of all possible next states
 * with their transition cost. For this purpose,
 * a special {@link CostWithNextCostCounter} class should be used.
 * This class contains the next state and the transition cost
 * with the predicate value, which are defined in accordance
 * with the original paper. Thus, {@code List<CostWithNextCostCounter>}
 * should be returned by these methods and an empty list should
 * be returned in case no transitions are possible.
 * In order to restrict the number of possible transitions,
 * the relaxation factor should be used. It is provided via
 * a constructor, so {@code Lin-Check} uses the
 * {@code (int relaxationFactor)} constructor for the first
 * instance creation.
 */
class QuantitativelyRelaxedLinearizabilityVerifier(
    scenario: ExecutionScenario,
    sequentialSpecification: Class<*>
) : AbstractLTSVerifier<LTS.State>(scenario, sequentialSpecification) {
    private val relaxationFactor: Int
    private val pathCostFunc: PathCostFunction
    override val lts: LTS

    init {
        val conf = sequentialSpecification.getAnnotation(QuantitativeRelaxationVerifierConf::class.java)
        checkNotNull(conf) { "QuantitativeRelaxationVerifierConf is not specified for the sequential specification " +
                             "$sequentialSpecification, the verifier can not be initialised." }
        relaxationFactor = conf.factor
        pathCostFunc = conf.pathCostFunc
        lts = LTS(
            sequentialSpecification = sequentialSpecification,
            isQuantitativelyRelaxed = true,
            relaxationFactor = relaxationFactor
        )
    }

    override fun createInitialContext(results: ExecutionResult): VerifierContext<LTS.State> =
        QuantitativelyRelaxedLinearizabilityContext(scenario, lts.initialState, results, relaxationFactor, pathCostFunc)
}






