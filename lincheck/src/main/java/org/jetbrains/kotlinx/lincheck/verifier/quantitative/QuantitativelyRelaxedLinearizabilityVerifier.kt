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
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.jetbrains.kotlinx.lincheck.verifier.VerifierContext

/**
 * This verifier checks for quantitative relaxation contracts, which are introduced
 * in the "Quantitative relaxation of concurrent data structures" paper by Henzinger et al.
 *
 * Requires [QuantitativeRelaxationVerifierConf] annotation on the testing class.
 */
class QuantitativelyRelaxedLinearizabilityVerifier(
    scenario: ExecutionScenario,
    testClass: Class<*>
) : AbstractLTSVerifier<LTS.State>(scenario, testClass) {
    private val relaxationFactor: Int
    private val pathCostFunc: PathCostFunction
    private val lts: LTS
    private val costCounter: Class<*> // cost counter?

    init {
        val conf = testClass.getAnnotation(QuantitativeRelaxationVerifierConf::class.java)
        checkNotNull(conf) { "QuantitativeRelaxationVerifierConf is not specified for the test class. " +
                "QuantitativelyRelaxedLinearizabilityVerifier can not be initialised." }
        relaxationFactor = conf.factor
        pathCostFunc = conf.pathCostFunc
        costCounter = conf.costCounter.java
        lts = LTS(
            testClass = costCounter,
            isQuantitativelyRelaxed = true,
            relaxationFactor = relaxationFactor
        )
    }

    override fun createInitialContext(results: ExecutionResult): VerifierContext<LTS.State> =
        QuantitativelyRelaxedLinearizabilityContext(scenario, lts.initialState, results, relaxationFactor, pathCostFunc)
}






