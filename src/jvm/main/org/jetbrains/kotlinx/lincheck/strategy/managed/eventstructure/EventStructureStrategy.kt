/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

class EventStructureStrategy(
        testCfg: EventStructureCTestConfiguration,
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunctions: List<Method>,
        stateRepresentation: Method?,
        verifier: Verifier
) : ManagedStrategy(testClass, scenario, verifier, validationFunctions, stateRepresentation, testCfg) {
    // The number of invocations that the strategy is eligible to use to search for an incorrect execution.
    private val maxInvocations = testCfg.invocationsPerIteration
    // The number of already used invocations.
    private var usedInvocations = 0

    private val initialThreadId = nThreads

    private val seqCstChecker: SequentialConsistencyChecker = SequentialConsistencyChecker()

    private val atomicityChecker: AtomicityChecker = AtomicityChecker()

    private val eventStructure: EventStructure =
        EventStructure(initialThreadId, listOf(seqCstChecker), listOf(atomicityChecker))

    // Tracker of shared memory accesses.
    override val memoryTracker: MemoryTracker = EventStructureMemoryTracker(eventStructure)
    // Tracker of acquisitions and releases of monitors.
    // TODO: change to EventStructureMonitorTracker
    override var monitorTracker: MonitorTracker = SeqCstMonitorTracker(nThreads)

    var consistentExecutions: Int = 0
        private set

    init {
        atomicityChecker.initialize(eventStructure)
    }

    override fun runImpl(): LincheckFailure? {
        // TODO: move invocation counting logic to ManagedStrategy class
        outer@while (usedInvocations < maxInvocations) {
            inner@while (eventStructure.startNextExploration()) {
                val result = runInvocation()
                // check that there were no inconsistencies detected during the run
                if (result is UnexpectedExceptionInvocationResult &&
                    result.exception is InconsistentExecutionException) {
                    continue@inner
                }
                // check that the final execution is consistent
                if (eventStructure.checkConsistency() != null) {
                    continue@inner
                }
                consistentExecutions++
                // TODO: should we count failed inconsistent executions as used invocations?
                usedInvocations++
                checkResult(result, shouldCollectTrace = false)?.let { return it }
                continue@outer
            }
            return null
        }
        return null
    }

    override fun shouldSwitch(iThread: Int): Boolean {
        // If strategy is in replay mode for given thread
        // we should wait until replaying the next event become possible
        // (i.e. when all the dependencies will be replayed too)
        if (eventStructure.inReplayMode(iThread)) {
            return !eventStructure.canReplayNextEvent(iThread)
        }
        // For event structure strategy enforcing context switches is not necessary,
        // because it is guaranteed that the strategy will explore all
        // executions anyway, no matter of the order of context switches.
        // Therefore we explore threads in fixed static order,
        // and thus this method always returns false.
        // In practice, however, the order of context switches may influence performance
        // of the model checking, and time-to-first-bug-discovered metric.
        // Thus we might want to customize scheduling strategy.
        // TODO: make scheduling strategy configurable
        return false
    }

    override fun chooseThread(iThread: Int): Int {
        // see comment in `shouldSwitch` method
        // TODO: make scheduling strategy configurable
        return switchableThreads(iThread).first()
    }

    override fun isActive(iThread: Int): Boolean {
        return super.isActive(iThread) &&
            (eventStructure.inReplayMode(iThread) implies { eventStructure.canReplayNextEvent(iThread) })
    }

    override fun initializeInvocation() {
        super.initializeInvocation()
        eventStructure.initializeExploration()
        // TODO: fix monitorTracker
        monitorTracker = SeqCstMonitorTracker(nThreads)
        eventStructure.addThreadStartEvent(initialThreadId)
    }

    override fun beforeParallelPart() {
        super.beforeParallelPart()
        eventStructure.addThreadForkEvent(initialThreadId, (0 until nThreads).toSet())
    }

    override fun afterParallelPart() {
        super.afterParallelPart()
        eventStructure.addThreadJoinEvent(initialThreadId, (0 until nThreads).toSet())
    }

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        eventStructure.addThreadStartEvent(iThread)
    }

    override fun onFinish(iThread: Int) {
        // TODO: recheck `currentThread` bookkeeping in ManagedStrategy
        //  to avoid data-races
        eventStructure.addThreadFinishEvent(iThread)
        super.onFinish(iThread)
    }
}