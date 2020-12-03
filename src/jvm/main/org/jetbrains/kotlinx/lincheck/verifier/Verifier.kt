/*
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
package org.jetbrains.kotlinx.lincheck.verifier

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario

/**
 * Implementation of this interface verifies that execution is correct with respect to the algorithm contract.
 * By default, it checks for linearizability (see [LinearizabilityVerifier]).
 *
 *
 * IMPORTANT!
 * All implementations should have `(Class<?> sequentialSpecification)` constructor,
 * which takes the scenario to be tested and the correct sequential implementation of the testing data structure.
 */
interface Verifier {
    /**
     * Verifies the specified results for correctness.
     * Returns `true` if results are possible, `false` otherwise.
     */
    fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult): Boolean

    /**
     * Verifiers which use sequential implementation instances as states (or parts of them)
     * should check whether [.equals] and [.hashCode] methods are implemented
     * correctly.
     */
    fun checkStateEquivalenceImplementation()
}