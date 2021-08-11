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

class BufferedDurableLinearizabilityVerifier(sequentialSpecification: Class<*>) : AbstractLTSVerifier(sequentialSpecification) {
    override val lts: LTS = LTS(sequentialSpecification = sequentialSpecification)

    override fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult): VerifierContext =
        BufferedDurableLinearizabilityContext(scenario, results, lts.initialState)
}

private class BufferedDurableLinearizabilityContext : AbstractLinearizabilityContext {
    private val persisted: List<LTS.State>
    private val deferredCrash: CrashResult?

    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State) : super(scenario, results, state) {
        persisted = listOf(state)
        deferredCrash = null
    }

    constructor(
        scenario: ExecutionScenario,
        results: ExecutionResult,
        state: LTS.State,
        executed: IntArray,
        suspended: BooleanArray,
        tickets: IntArray,
        persisted: List<LTS.State>,
        deferredCrash: CrashResult?
    ) : super(scenario, results, state, executed, suspended, tickets) {
        this.persisted = persisted
        this.deferredCrash = deferredCrash
    }

    override fun createContainer(): AbstractLinearizabilityContext.Container = Container()

    override fun processResult(container: AbstractLinearizabilityContext.Container, threadId: Int) {
        val actorId = executed[threadId]
        val result = results[threadId][actorId]
        if (result is CrashResult) {
            if (result.isLastInSystemCrash(threadId)) {
                val newExecuted = executed.copyOf()
                newExecuted[threadId]++
                addContextsUpToSync(container, newExecuted)
                return
            }
        }
        container
            .filterIsInstance<BufferedDurableLinearizabilityContext>()
            .firstOrNull { context -> context.deferredCrash !== null && context.run { context.deferredCrash.isLastInSystemCrash(threadId) } }
            ?.addContextsUpToSync(container)
    }

    private fun addContextsUpToSync(container: AbstractLinearizabilityContext.Container, newExecuted: IntArray = executed) {
        for (q in persisted) {
            container.addContext(BufferedDurableLinearizabilityContext(scenario, results, q, newExecuted, suspended, tickets, listOf(q), null))
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
        val isSystemCrash = result is CrashResult && result.isLastInSystemCrash(threadId)
        val newPersisted = if (isSync || isSystemCrash) listOf(state) else persisted.plus(state)
        val deferredCrash = result as? CrashResult ?: this.deferredCrash
        return BufferedDurableLinearizabilityContext(scenario, results, state, executed, suspended, tickets, newPersisted, deferredCrash)
    }

    private fun CrashResult.isLastInSystemCrash(threadId: Int) =
        crashedActors.withIndex().all { it.index + 1 == threadId || executed[it.index + 1] >= it.value }

    private class Container : AbstractLinearizabilityContext.Container {
        private val data = mutableListOf<VerifierContext>()
        override fun iterator(): Iterator<VerifierContext> = data.iterator()
        override fun addContext(context: VerifierContext) {
            data.add(context)
        }
    }
}

private fun Actor.isSync() = this.method.annotations.any { it.annotationClass == Sync::class }
