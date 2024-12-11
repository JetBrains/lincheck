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

/**
 * Interface representing the statistics collected during the execution of a Lincheck test.
 */
interface LincheckStatistics {
    /**
     * Total running time in nanoseconds, excluding warm-up time.
     */
    val runningTimeNano: Long

    /**
     * A mapping from iteration id to the iteration statistics.
     */
    val iterationsStatistics: Map<Int, LincheckIterationStatistics>

    /**
     * Granularity at which running time is captured, either:
     *   - [StatisticsGranularity.PER_ITERATION] --- per iteration only;
     *   - [StatisticsGranularity.PER_INVOCATION] --- per each invocation (consumes more memory).
     */
    val granularity: StatisticsGranularity
}

/**
 * Represents the statistics of a single Lincheck test iteration.
 */
interface LincheckIterationStatistics {
    /**
     * Used scenario.
     */
    val scenario: ExecutionScenario

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

/**
 * Represents the different granularities at which Lincheck statistics data can be collected.
 */
enum class StatisticsGranularity {
    PER_ITERATION, PER_INVOCATION
}

/**
 * Number of performed iterations.
 */
val LincheckStatistics.iterationsCount: Int
    get() = iterationsStatistics.size

/**
 * Total running time, including warm-up time.
 */
val LincheckStatistics.totalRunningTimeNano: Long
    get() = runningTimeNano + warmUpTimeNano

/**
 * Total warm-up time in nanoseconds, spent on all iterations.
 */
val LincheckStatistics.warmUpTimeNano: Long
    get() = iterationsStatistics.values.sumOf { it.warmUpTimeNano }

/**
 * Number of invocations performed on all iterations.
 */
val LincheckStatistics.invocationsCount: Int
    get() = iterationsStatistics.values.sumOf { it.invocationsCount }

/**
 * Total number of performed invocations, including warm-up invocations
 */
val LincheckStatistics.totalInvocationsCount: Int
    get() = iterationsStatistics.values.sumOf { it.totalInvocationsCount }

/**
 * Average number of invocations performed by iteration.
 */
val LincheckStatistics.averageInvocationsCount: Double
    get() = iterationsStatistics.values.map { it.invocationsCount }.average()

/**
 * The average invocation time (across all iterations) in nanoseconds.
 */
val LincheckStatistics.averageInvocationTimeNano
    get() = runningTimeNano.toDouble() / invocationsCount

/**
 * Total running time of this iteration in nanoseconds, including warm-up time.
 */
val LincheckIterationStatistics.totalRunningTimeNano: Long
    get() = runningTimeNano + warmUpTimeNano

/**
 * Total number of invocations performed on this iteration, including warm-up invocations.
 */
val LincheckIterationStatistics.totalInvocationsCount: Int
    get() = invocationsCount + warmUpInvocationsCount

/**
 * Average invocation time on given iteration.
 */
val LincheckIterationStatistics.averageInvocationTimeNano
    get() = runningTimeNano.toDouble() / invocationsCount


class LincheckStatisticsTracker(
    override val granularity: StatisticsGranularity = StatisticsGranularity.PER_ITERATION
) : LincheckStatistics, LincheckRunTracker {

    override var runningTimeNano: Long = 0
        private set

    override val iterationsStatistics: Map<Int, LincheckIterationStatistics>
        get() = _iterationsStatistics
    private val _iterationsStatistics = mutableMapOf<Int, IterationStatisticsTracker>()

    private class IterationStatisticsTracker(
        override val scenario: ExecutionScenario,
        val parameters: IterationParameters,
        granularity: StatisticsGranularity,
    ) : LincheckIterationStatistics {
        override var runningTimeNano: Long = 0
        override var warmUpTimeNano: Long = 0
        override var invocationsCount: Int = 0
        override var warmUpInvocationsCount: Int = 0
        override val invocationsRunningTimeNano: LongArray = when (granularity) {
            StatisticsGranularity.PER_ITERATION -> longArrayOf()
            StatisticsGranularity.PER_INVOCATION -> LongArray(parameters.invocationsBound)
        }
        var lastInvocationStartTimeNano = -1L
    }

    private val IterationStatisticsTracker.plannedWarmUpInvocationsCount: Int
        get() = parameters.warmUpInvocationsCount

    override fun iterationStart(iteration: Int, scenario: ExecutionScenario, parameters: IterationParameters) {
        check(iteration !in iterationsStatistics)
        _iterationsStatistics[iteration] = IterationStatisticsTracker(scenario, parameters, granularity)
    }

    override fun invocationStart(iteration: Int, invocation: Int) {
        val statistics = _iterationsStatistics[iteration]!!
        statistics.lastInvocationStartTimeNano = System.nanoTime()
    }

    override fun invocationEnd(iteration: Int, invocation: Int, failure: LincheckFailure?, exception: Throwable?) {
        val statistics = _iterationsStatistics[iteration]!!
        val invocationTimeNano = System.nanoTime() - statistics.lastInvocationStartTimeNano
        check(invocationTimeNano >= 0)
        if (invocation < statistics.plannedWarmUpInvocationsCount) {
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
        statistics.lastInvocationStartTimeNano = -1L
    }

}

