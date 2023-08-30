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
     * Total running time in nanoseconds, excluding warm-up time.
     */
    val runningTimeNano: Long

    /**
     * A list of iteration statistics.
     */
    val iterationsStatistics: List<IterationStatistics>

    /**
     * Granularity at which running time is captures, either:
     *   - [StatisticsGranularity.PER_ITERATION] --- per iteration only;
     *   - [StatisticsGranularity.PER_INVOCATION] --- per each invocation (consumes more memory).
     */
    val granularity: StatisticsGranularity
}

interface IterationStatistics {
    /**
     * Used scenario.
     */
    val scenario: ExecutionScenario

    /**
     * Used mode.
     */
    val mode: LincheckMode

    /**
     * Running time of this iteration in nanoseconds, excluding warm-up time.
     */
    val runningTimeNano: Long

    /**
     * Warm-up time of this iteration in nanoseconds.
     */
    val warmUpTimeNano: Long

    /**
     * Number of invocations performed on this iteration, excluding warm-up invocations.
     */
    val invocationsCount: Int

    /**
     * Number of warm-up invocations performed on this iteration.
     */
    val warmUpInvocationsCount: Int

    /**
     * Running time of all invocations in this iteration.
     * If per-iteration statistics tracking granularity is specified,
     * then running time of invocations is not collected, and this array is left empty.
     */
    val invocationsRunningTimeNano: LongArray
}

enum class StatisticsGranularity {
    PER_ITERATION, PER_INVOCATION
}

/**
 * Number of performed iterations.
 */
val Statistics.iterationsCount: Int
    get() = iterationsStatistics.size

/**
 * Total running time, including warm-up time.
 */
val Statistics.totalRunningTimeNano: Long
    get() = runningTimeNano + warmUpTimeNano

/**
 * Total warm-up time in nanoseconds, spent on all iterations.
 */
val Statistics.warmUpTimeNano: Long
    get() = iterationsStatistics.sumOf { it.warmUpTimeNano }

/**
 * Number of invocations performed on all iterations.
 */
val Statistics.invocationsCount: Int
    get() = iterationsStatistics.sumOf { it.invocationsCount }

/**
 * Total number of performed invocations, including warm-up invocations
 */
val Statistics.totalInvocationsCount: Int
    get() = iterationsStatistics.sumOf { it.totalInvocationsCount }

/**
 * Average number of invocations performed by iteration.
 */
val Statistics.averageInvocationsCount: Double
    get() = iterationsStatistics.map { it.invocationsCount }.average()

/**
 * The average invocation time (across all iterations) in nanoseconds.
 */
val Statistics.averageInvocationTimeNano
    get() = runningTimeNano.toDouble() / invocationsCount

/**
 * Total running time of this iteration in nanoseconds, including warm-up time.
 */
val IterationStatistics.totalRunningTimeNano: Long
    get() = runningTimeNano + warmUpTimeNano

/**
 * Total number of invocations performed on this iteration, including warm-up invocations.
 */
val IterationStatistics.totalInvocationsCount: Int
    get() = invocationsCount + warmUpInvocationsCount

/**
 * Average invocation time on given iteration.
 */
val IterationStatistics.averageInvocationTimeNano
    get() = runningTimeNano.toDouble() / invocationsCount


class StatisticsTracker(
    override val granularity: StatisticsGranularity = StatisticsGranularity.PER_ITERATION
) : Statistics, RunTracker {

    override var runningTimeNano: Long = 0
        private set

    override val iterationsStatistics: List<IterationStatistics>
        get() = _iterationsStatistics
    private val _iterationsStatistics = mutableListOf<IterationStatisticsTracker>()

    private class IterationStatisticsTracker(
        override val scenario: ExecutionScenario,
        options: IterationOptions,
        granularity: StatisticsGranularity,
    ) : IterationStatistics {
        override val mode: LincheckMode = options.mode
        override var runningTimeNano: Long = 0
        override var warmUpTimeNano: Long = 0
        override var invocationsCount: Int = 0
        override var warmUpInvocationsCount: Int = 0
        override val invocationsRunningTimeNano: LongArray = when (granularity) {
            StatisticsGranularity.PER_ITERATION -> longArrayOf()
            StatisticsGranularity.PER_INVOCATION -> LongArray(options.invocationsBound)
        }
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
     * Number of invocations in the current iteration.
     */
    val currentIterationInvocationsCount: Int
        get() = iterationsStatistics[iteration].invocationsCount

    val currentIterationWarmUpInvocationsCount: Int
        get() = iterationsStatistics[iteration].warmUpInvocationsCount

    // flag indicating that next invocations should be considered warm-up
    private var warmUpFlag: Boolean = false

    override fun iterationStart(iteration: Int, scenario: ExecutionScenario, options: IterationOptions) {
        check(iteration == this.iteration + 1)
        ++this.iteration
        _iterationsStatistics.add(
            IterationStatisticsTracker(scenario, options, granularity)
        )
        warmUpFlag = false
    }

    override fun iterationEnd(iteration: Int, failure: LincheckFailure?, exception: Throwable?) {
        check(iteration == this.iteration)
        invocation = -1
    }

    private var lastInvocationStartTimeNano = -1L

    override fun invocationStart(invocation: Int) {
        check(invocation == this.invocation + 1)
        ++this.invocation
        lastInvocationStartTimeNano = System.nanoTime()
    }

    override fun invocationEnd(invocation: Int, failure: LincheckFailure?, exception: Throwable?) {
        check(invocation == this.invocation)
        val invocationTimeNano = System.nanoTime() - lastInvocationStartTimeNano
        check(invocationTimeNano >= 0)
        val statistics = _iterationsStatistics[iteration]
        if (warmUpFlag) {
            statistics.warmUpTimeNano += invocationTimeNano
            statistics.warmUpInvocationsCount += 1
        } else {
            statistics.runningTimeNano += invocationTimeNano
            statistics.invocationsCount += 1
            runningTimeNano += invocationTimeNano
        }
        if (granularity == StatisticsGranularity.PER_INVOCATION) {
            statistics.invocationsRunningTimeNano[invocation] = invocationTimeNano
        }
    }

    fun iterationWarmUpStart(iteration: Int) {
        check(iteration == this.iteration)
        check(iterationsStatistics[this.iteration].warmUpTimeNano == 0L)
        warmUpFlag = true
    }

    fun iterationWarmUpEnd(iteration: Int) {
        check(iteration == this.iteration)
        warmUpFlag = false
    }

}

