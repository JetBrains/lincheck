/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure

interface Statistics {
    /**
     * Total amount of time already spent on testing.
     */
    val runningTimeNano: Long
    /**
     * A list of iteration statistics.
     */
    val iterationsStatistics: List<IterationStatistics>
}

interface IterationStatistics {
    val runningTimeNano: Long
    val invocationsCount: Int
}

/**
 * Number of performed iterations, that is run scenarios.
 */
val Statistics.iterationsCount: Int
    get() = iterationsStatistics.size

/**
 * Total number of performed invocations.
 */
val Statistics.totalInvocationsCount: Int
    get() = iterationsStatistics.sumOf { it.invocationsCount }

/**
 * Average number of invocations performed by iteration.
 */
val Statistics.averageInvocations: Double
    get() = iterationsStatistics.map { it.invocationsCount }.average()

/**
 * The average invocation time (across all iterations) in nanoseconds.
 */
val Statistics.averageInvocationTimeNano
    get() = runningTimeNano.toDouble() / totalInvocationsCount

/**
 * Average invocation time on given iteration.
 */
val IterationStatistics.averageInvocationTimeNano
    get() = runningTimeNano.toDouble() / invocationsCount

internal class StatisticsTracker : Statistics, RunTracker {

    override var runningTimeNano: Long = 0
        private set

    override val iterationsStatistics: List<IterationStatistics>
        get() = _iterationsStatistics
    private val _iterationsStatistics = mutableListOf<IterationStatisticsTracker>()

    private class IterationStatisticsTracker : IterationStatistics {
        override var runningTimeNano: Long = 0
        override var invocationsCount: Int = 0
    }

    /**
     * Current iteration number.
     */
    var iteration: Int = -1
        private set

    /**
     * Current invocation number within current iteration.
     */
    var invocation: Int = -1
        private set

    /**
     * Running time of current iteration.
     */
    val currentIterationRunningTimeNano: Long
        get() = iterationsStatistics[iteration].runningTimeNano

    /**
     * Number of invocations in current iteration.
     */
    val currentIterationInvocationsCount: Int
        get() = iterationsStatistics[iteration].invocationsCount

    override fun iterationStart(iteration: Int, scenario: ExecutionScenario) {
        check(iteration == this.iteration + 1)
        ++this.iteration
        _iterationsStatistics.add(IterationStatisticsTracker())
    }

    override fun iterationEnd(iteration: Int, failure: LincheckFailure?) {
        invocation = -1
    }

    private var lastInvocationStartTimeNano = -1L

    override fun invocationStart(invocation: Int) {
        check(invocation == this.invocation + 1)
        ++this.invocation
        lastInvocationStartTimeNano = System.nanoTime()
    }

    override fun invocationEnd(invocation: Int, failure: LincheckFailure?) {
        val invocationTimeNano = System.nanoTime() - lastInvocationStartTimeNano
        check(invocationTimeNano >= 0)
        runningTimeNano += invocationTimeNano
        _iterationsStatistics[iteration].runningTimeNano += invocationTimeNano
        _iterationsStatistics[iteration].invocationsCount += 1
    }

}

