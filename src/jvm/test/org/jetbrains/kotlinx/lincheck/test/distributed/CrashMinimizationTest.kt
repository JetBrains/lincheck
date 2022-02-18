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

package org.jetbrains.kotlinx.lincheck.test.distributed

import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.NodeCrashEvent
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.junit.Test

class CrashMinimizationVerifier : DistributedVerifier {
    override fun verifyResultsAndStates(
        nodes: Array<out Node<*>>,
        scenario: ExecutionScenario,
        results: ExecutionResult,
        events: List<Event>
    ): Boolean {
        return events.none { it is NodeCrashEvent }
    }
}


class CrashMinimizationTest {
    @Test
    fun test() {
        val failure = DistributedOptions<Int>()
            .addNodes<CrashingNode>(
                nodes = 4,
                minNodes = 2,
                crashMode = CrashMode.RECOVER_ON_CRASH,
                networkPartition = NetworkPartitionMode.COMPONENTS
            )
            .verifier(CrashMinimizationVerifier::class.java)
            .iterations(1)
            .invocationsPerIteration(500_000)
            .storeLogsForFailedScenario("crash_minimization.txt")
            .checkImpl(CrashingNode::class.java)
        assert(failure is IncorrectResultsFailure)
        assert(failure!!.crashes == 1)
        assert(failure.partitions == 0)
        assert(failure.scenario.threads == 1 && failure.scenario.parallelExecution[0].size == 1)
    }
}
