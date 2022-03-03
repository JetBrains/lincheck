/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*

/**
 * This verifier checks that the specified results could happen if the testing operations are linearizable.
 * For that, it tries to find a possible sequential execution where transitions do not violate both
 * LTS (see [LTS]) transitions and the happens-before order. Essentially, it tries to execute the next actor
 * in each thread and goes deeper until all actors are executed.
 *
 * This verifier is based on [AbstractLTSVerifier] and caches the already processed results
 * for performance improvement (see [CachedVerifier]).
 */
class LinearizabilityVerifier(sequentialSpecification: Class<*>) : AbstractLTSVerifier(sequentialSpecification) {
    override val lts: LTS = LTS(sequentialSpecification = sequentialSpecification)

    override fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult): VerifierContext =
        LinearizabilityContext(scenario, results, lts.initialState)
}

private class LinearizabilityContext : AbstractLinearizabilityContext {
    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State)
        : super(scenario, results, state)

    constructor(
        scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State,
        executed: IntArray, suspended: BooleanArray, tickets: IntArray
    ) : super(scenario, results, state, executed, suspended, tickets)

    override fun processResult(nextContexts: ContextsList, threadId: Int) = nextContexts

    override fun createContext(
        threadId: Int,
        scenario: ExecutionScenario,
        results: ExecutionResult,
        state: LTS.State,
        executed: IntArray,
        suspended: BooleanArray,
        tickets: IntArray
    ) = LinearizabilityContext(scenario, results, state, executed, suspended, tickets)
}