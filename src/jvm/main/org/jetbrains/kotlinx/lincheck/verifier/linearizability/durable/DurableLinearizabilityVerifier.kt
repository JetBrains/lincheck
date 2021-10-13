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

package org.jetbrains.kotlinx.lincheck.verifier.linearizability.durable

import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.get
import org.jetbrains.kotlinx.lincheck.verifier.AbstractLTSVerifier
import org.jetbrains.kotlinx.lincheck.verifier.ContextsList
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.jetbrains.kotlinx.lincheck.verifier.VerifierContext
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.AbstractLinearizabilityContext

/**
 * Verifier for durable linearizability.
 *
 * This criterion requires all successfully operations to be linearizable, while the interrupted by a crash operations may be not linearizable.
 * @see org.jetbrains.kotlinx.lincheck.nvm.Recover.DURABLE
 */
internal class DurableLinearizabilityVerifier(sequentialSpecification: Class<*>) : AbstractLTSVerifier(sequentialSpecification) {
    override val lts: LTS = LTS(sequentialSpecification = sequentialSpecification)

    override fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult): VerifierContext =
        DurableLinearizabilityContext(scenario, results, lts.initialState)
}

private class DurableLinearizabilityContext : AbstractLinearizabilityContext {
    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State)
        : super(scenario, results, state)

    constructor(
        scenario: ExecutionScenario,
        results: ExecutionResult,
        state: LTS.State,
        executed: IntArray,
        suspended: BooleanArray,
        tickets: IntArray
    ) : super(scenario, results, state, executed, suspended, tickets)

    /** If this operation has been crashed, it may be skipped. */
    override fun processResult(nextContexts: ContextsList, threadId: Int): ContextsList {
        val actorId = executed[threadId]
        val expectedResult = results[threadId][actorId]
        if (expectedResult is CrashResult) {
            return nextContexts + skipOperation(threadId)
        }
        return nextContexts
    }

    override fun createContext(
        threadId: Int,
        scenario: ExecutionScenario,
        results: ExecutionResult,
        state: LTS.State,
        executed: IntArray,
        suspended: BooleanArray,
        tickets: IntArray
    ) = DurableLinearizabilityContext(scenario, results, state, executed, suspended, tickets)

    private fun skipOperation(threadId: Int): DurableLinearizabilityContext {
        val newExecuted = executed.copyOf()
        newExecuted[threadId]++
        return DurableLinearizabilityContext(
            scenario = scenario,
            state = state,
            results = results,
            executed = newExecuted,
            suspended = suspended,
            tickets = tickets
        )
    }
}