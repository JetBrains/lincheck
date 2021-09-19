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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.annotations.Sync
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.get
import org.jetbrains.kotlinx.lincheck.verifier.AbstractLTSVerifier
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.jetbrains.kotlinx.lincheck.verifier.VerifierContext
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.AbstractLinearizabilityContext

internal class BufferedDurableLinearizabilityVerifier(sequentialSpecification: Class<*>) : AbstractLTSVerifier(sequentialSpecification) {
    override val lts: LTS = LTS(sequentialSpecification = sequentialSpecification)

    override fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult): VerifierContext =
        BufferedDurableLinearizabilityContext(scenario, results, lts.initialState)
}

private class BufferedDurableLinearizabilityContext : AbstractLinearizabilityContext {
    private val persisted: List<LTS.State>
    private val waitingThreadsToCrash: Int

    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State) : super(scenario, results, state) {
        persisted = listOf(state)
        waitingThreadsToCrash = 0
    }

    constructor(
        scenario: ExecutionScenario,
        results: ExecutionResult,
        state: LTS.State,
        executed: IntArray,
        suspended: BooleanArray,
        tickets: IntArray,
        persisted: List<LTS.State>,
        waitingThreadsToCrash: Int
    ) : super(scenario, results, state, executed, suspended, tickets) {
        this.persisted = persisted
        this.waitingThreadsToCrash = waitingThreadsToCrash
    }

    override fun createContainer(): AbstractLinearizabilityContext.Container = Container()

    override fun processResult(container: AbstractLinearizabilityContext.Container, threadId: Int) {
        val actorId = executed[threadId]
        val result = results[threadId][actorId]
        if (result is CrashResult || (waitingThreadsToCrash > 0 && !scenario[threadId][actorId].isSync())) {
            val context = container.filterIsInstance<BufferedDurableLinearizabilityContext>().firstOrNull { it.waitingThreadsToCrash == 0 }
            if (context !== null) {
                for (q in persisted) {
                    container.addContext(BufferedDurableLinearizabilityContext(scenario, results, q, context.executed, suspended, tickets, listOf(q), 0))
                }
            }
        }
    }

    override fun createContext(
        threadId: Int,
        scenario: ExecutionScenario,
        results: ExecutionResult,
        state: LTS.State,
        executed: IntArray,
        suspended: BooleanArray,
        tickets: IntArray
    ): AbstractLinearizabilityContext {
        val actorId = this.executed[threadId]
        val actor = scenario[threadId][actorId]
        val result = results[threadId][actorId]
        val isSync = actor.isSync() && result !is CrashResult
        val newWaiting = if (waitingThreadsToCrash == 0 && result is CrashResult) {
            result.crashedActors.withIndex().count { (if (it.value == scenario[it.index + 1].size) executed[it.index + 1] else executed[it.index + 1] - 1) < it.value }
        } else if (waitingThreadsToCrash > 0 && executed[threadId] == scenario[threadId].size || result is CrashResult) {
            waitingThreadsToCrash - 1
        } else waitingThreadsToCrash
        val isSystemCrash = newWaiting == 0 && (result is CrashResult || waitingThreadsToCrash > 0)
        val newPersisted = if (isSync || isSystemCrash) listOf(state) else persisted.plus(state)
        return BufferedDurableLinearizabilityContext(scenario, results, state, executed, suspended, tickets, newPersisted, newWaiting)
    }

    private class Container : AbstractLinearizabilityContext.Container {
        private val data = mutableListOf<VerifierContext>()
        override fun iterator(): Iterator<VerifierContext> = data.iterator()
        override fun addContext(context: VerifierContext) {
            data.add(context)
        }
    }
}

private fun Actor.isSync() = this.method.annotations.any { it.annotationClass == Sync::class }
