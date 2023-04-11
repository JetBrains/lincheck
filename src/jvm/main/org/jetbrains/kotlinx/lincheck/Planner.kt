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

import org.jetbrains.kotlinx.lincheck.runner.*
import kotlin.math.*

interface InvocationPlanner {
    fun shouldDoNextInvocation(): Boolean

    fun startMeasureInvocationTime()

    fun endMeasureInvocationTime()
}

internal inline fun InvocationPlanner.measureInvocationTime(block: () -> InvocationResult): InvocationResult {
    startMeasureInvocationTime()
    try {
        return block()
    } finally {
        endMeasureInvocationTime()
    }
}

internal class FixedInvocationPlanner(invocationsPerIteration: Int) : InvocationPlanner {
    private var remainingInvocations = invocationsPerIteration

    override fun shouldDoNextInvocation() = remainingInvocations > 0

    override fun startMeasureInvocationTime() {}

    override fun endMeasureInvocationTime() {
        remainingInvocations--
    }
}

/**
 * The class for planning the work distribution during one run.
 * In particular, it is responsible for the following activities:
 * - measuring the time of invocations run;
 * - keep the track of deadlines and remaining time;
 * - adaptively adjusting number of test scenarios and invocations allocated per scenario.
 */
internal class AdaptivePlanner (
    /**
     * Total amount of time in milliseconds allocated to testing.
     */
    val testingTime: Long,
    /**
     * A lower bound on the number of iterations that should be definitely performed, unless impossible.
     * This number is fixed at the beginning of testing and does not change.
     */
    val iterationsLowerBound: Int = 0
) : InvocationPlanner {
    /**
     * Total amount of time already spent on testing.
     */
    var runningTime: Long = 0
        private set

    /**
     * Remaining amount of time for testing.
     */
    val remainingTime: Long
        get() = testingTime - runningTime

    /**
     * The percent of time already spent. An integer number in range [0 .. 100].
     */
    val testingProgress: Int
        get() = round(100 * runningTime.toDouble() / testingTime).toInt()

    /**
     * Current iteration number.
      */
    var iteration: Int = 0
        private set

    /**
     * An optimistic upper bound on the number of iterations that are expected to be performed.
     * If automatic adjustment of iteration number is enabled, this variable can change during testing.
     */
    var iterationsUpperBound: Int = max(iterationsLowerBound, INITIAL_ITERATIONS_UPPER_BOUND)
        private set

    /**
     * An array keeping running time of all iterations.
     */
    val iterationsRunningTime: List<Long>
        get() = _iterationsRunningTime

    /**
     * Array keeping number of invocations executed for each iteration
     */
    val iterationsInvocationCount: List<Int>
        get() = _iterationsInvocationCount

    /**
     * Current invocation number within current iteration.
     */
    var invocation: Int = 0
        private set

    /**
     * Number of invocations per iteration.
     * If automatic adjustment of invocations number is enabled, this variable can change during testing.
     */
    var invocationsBound = INITIAL_INVOCATIONS_BOUND
        private set

    // an array keeping running time of all iterations
    private val _iterationsRunningTime = mutableListOf<Long>()

    // and array keeping number of invocations executed for each iteration
    private val _iterationsInvocationCount = mutableListOf<Int>()

    // an array keeping running time of last N invocations
    private val invocationsRunningTime =
        LongArray(ADJUSTMENT_THRESHOLD) { 0 }

    private val invocationIndex: Int
        get() = invocation % ADJUSTMENT_THRESHOLD

    private val averageInvocationTime: Double
        get() = invocationsRunningTime.average()

    fun shouldDoNextIteration(): Boolean =
        (remainingTime > 0) && (iteration < iterationsUpperBound)

    override fun shouldDoNextInvocation(): Boolean =
        (remainingTime > 0) && (invocation < invocationsBound)

    fun<T> measureIterationTime(block: () -> T): T {
        val runningTimeAtStart = runningTime
        return block().also {
            val elapsed = runningTime - runningTimeAtStart
            _iterationsRunningTime.add(elapsed)
            _iterationsInvocationCount.add(invocation)
            invocationsRunningTime.fill(0)
            iteration += 1
            invocation = 0
        }
    }

    private var lastInvocationStartTime = -1L

    override fun startMeasureInvocationTime() {
        lastInvocationStartTime = System.currentTimeMillis()
    }

    override fun endMeasureInvocationTime() {
        val elapsed = System.currentTimeMillis() - lastInvocationStartTime
        runningTime += elapsed
        invocationsRunningTime[invocationIndex] = elapsed
        if (++invocation == ADJUSTMENT_THRESHOLD) {
            adjustBounds()
            invocationsRunningTime.fill(0)
        }
    }

    private fun adjustBounds() {
        // first handle lower bound, make sure we are on schedule
        if (iteration < iterationsLowerBound) {
            val remainingIterations = (iterationsLowerBound - iteration - 1)
            val remainingInvocations = (invocationsBound - invocation) + remainingIterations * invocationsBound
            val timeEstimate = remainingInvocations * averageInvocationTime
            // if we under-perform, adjust the invocations bound to fit into deadline
            if (timeEstimate > remainingTime) {
                val invocationsEstimate = remainingTime.toDouble() / ((remainingIterations + 1) * averageInvocationTime)
                invocationsBound = max(INVOCATIONS_LOWER_BOUND, floor(invocationsEstimate).toInt())
                return
            }
        }

        // next handle the optimistic upper bound
        val remainingIterations = (iterationsUpperBound - iteration - 1)
        val remainingInvocations = (invocationsBound - invocation) + remainingIterations * invocationsBound
        val timeEstimate = remainingInvocations * averageInvocationTime
        // if we under-perform by an order of magnitude, adjust the invocations bound to fit into deadline
        if (timeEstimate / remainingTime > INVOCATIONS_FACTOR) {
            invocationsBound = max(INVOCATIONS_LOWER_BOUND, invocationsBound / INVOCATIONS_FACTOR)
            return
        }
        // if we over-perform by and order of magnitude, increase the number of invocations per iteration
        if (remainingTime / timeEstimate > INVOCATIONS_FACTOR) {
            invocationsBound = min(INVOCATIONS_UPPER_BOUND, invocationsBound * INVOCATIONS_FACTOR)
            return
        }

        // if we under-perform, decrease the number of planned iterations
        if (remainingTime < timeEstimate) {
            // how bad is situation?
            val delay = timeEstimate - remainingTime
            val delayingIterations = floor(delay / (invocationsBound * averageInvocationTime)).toInt()
            // remove the iterations we are unlikely to reach
            iterationsUpperBound -= delayingIterations
            return
        }
        val invocationsDelta = ITERATIONS_DELTA * invocationsBound
        // if we over-perform, schedule more iterations
        if (remainingTime - timeEstimate > invocationsDelta * averageInvocationTime) {
            iterationsUpperBound += ITERATIONS_DELTA
            return
        }
    }

    companion object {
        // initial iterations upper bound
        private const val INITIAL_ITERATIONS_UPPER_BOUND = 30

        // number of iterations added/subtracted when we over- or under-perform the plan
        private const val ITERATIONS_DELTA = 5

        // factor of invocations multiplied/divided by when we over- or under-perform the plan
        // by an order of magnitude
        private const val INVOCATIONS_FACTOR = 5

        // initial number of invocations
        private const val INITIAL_INVOCATIONS_BOUND = 5_000

        // lower bound of invocations allocated by iteration
        private const val INVOCATIONS_LOWER_BOUND = 1_000

        // upper bound of invocations allocated by iteration
        private const val INVOCATIONS_UPPER_BOUND = 100_000

        // number of invocations performed between dynamic parameter adjustments
        private const val ADJUSTMENT_THRESHOLD = 100
    }

}