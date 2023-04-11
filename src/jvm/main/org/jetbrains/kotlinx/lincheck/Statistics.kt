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
     * An array keeping running time of all iterations.
     * TODO: refactor to function returning time of i-th iteration
     */
    val iterationsRunningTimeNano: List<Long>

    /**
     * Array keeping number of invocations executed for each iteration
     * TODO: refactor to function returning count of i-th iteration
     */
    val iterationsInvocationsCount: List<Int>

    companion object {
        val empty = object : Statistics {
            override val runningTimeNano: Long = 0
            override val iterationsRunningTimeNano: List<Long> = listOf()
            override val iterationsInvocationsCount: List<Int> = listOf()
        }
    }

}

/**
 * Number of performed iterations, that is run scenarios.
 */
val Statistics.iterations: Int
    get() = iterationsRunningTimeNano.size

/**
 * Average number of invocations performed by iteration.
 */
val Statistics.averageInvocations: Double
    get() = iterationsInvocationsCount.average()

operator fun Statistics.plus(statistics: Statistics) = object : Statistics {
    override val runningTimeNano: Long =
        this@plus.runningTimeNano + statistics.runningTimeNano

    override val iterationsRunningTimeNano: List<Long> =
        this@plus.iterationsRunningTimeNano + statistics.iterationsRunningTimeNano

    override val iterationsInvocationsCount: List<Int> =
        this@plus.iterationsInvocationsCount + statistics.iterationsInvocationsCount
}

/**
 * Average number of invocations per iteration after performing given [iteration].
 */
fun Statistics.averageInvocationsCount(iteration: Int): Double =
    iterationsInvocationsCount.take(iteration).average()

/**
 * Average invocation time on given [iteration].
 */
fun Statistics.averageInvocationTimeNano(iteration: Int): Double =
    iterationsRunningTimeNano[iteration] / iterationsInvocationsCount[iteration].toDouble()

internal class StatisticsTracker : Statistics, RunTracker {

    override var runningTimeNano: Long = 0
        private set

    override val iterationsRunningTimeNano: List<Long>
        get() = _iterationsRunningTimeNano
    private val _iterationsRunningTimeNano = mutableListOf<Long>()

    override val iterationsInvocationsCount: List<Int>
        get() = _iterationsInvocationCount
    private val _iterationsInvocationCount = mutableListOf<Int>()

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
        get() = iterationsRunningTimeNano[iteration]

    /**
     * Number of invocations in current iteration.
     */
    val currentIterationInvocationsCount: Int
        get() = iterationsInvocationsCount[iteration]

    override fun iterationStart(iteration: Int, scenario: ExecutionScenario) {
        check(iteration == this.iteration + 1)
        ++this.iteration
        _iterationsRunningTimeNano.add(0)
        _iterationsInvocationCount.add(0)
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
        _iterationsInvocationCount[iteration] += 1
        _iterationsRunningTimeNano[iteration] += invocationTimeNano
    }

}