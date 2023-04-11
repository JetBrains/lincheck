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

internal class OldApiInvocationPlanner(invocationsPerIteration: Int) : InvocationPlanner {
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
internal class AdaptivePlanner(options: LincheckOptionsImpl) {

    /**
     * Total amount of time in milliseconds allocated to testing.
     */
    val testingTime: Long = 1000 * options.testingTimeInSeconds

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
     * The current test running mode that should be used.
     */
    internal val mode: LincheckMode
        get() = when (originalMode) {
            LincheckMode.Stress         -> LincheckMode.Stress
            LincheckMode.ModelChecking  -> LincheckMode.ModelChecking
            LincheckMode.Hybrid         -> {
                if (testingProgress < STRATEGY_SWITCH_THRESHOLD)
                    LincheckMode.Stress
                else
                    LincheckMode.ModelChecking
            }
        }

    /**
     * An array keeping running time of all iterations.
     */
    val iterationsRunningTime: List<Long>
        get() = _iterationsRunningTime

    /**
     * Current iteration number.
      */
    var iteration: Int = 0
        private set

    /**
     * Array keeping number of invocations executed for each iteration
     */
    val iterationsInvocationCount: List<Int>
        get() = _iterationsInvocationCount

    /**
     * A lower bound on the number of iterations that should be definitely performed,
     * unless impossible (includes the number of custom scenarios).
     * This number is fixed at the beginning of testing and does not change.
     */
    val iterationsLowerBound: Int = options.iterationsLowerBound()

    /**
     * An optimistic upper bound on the number of iterations that are expected to be performed.
     * If automatic adjustment of iteration number is enabled, this variable can change during testing.
     */
    var iterationsUpperBound: Int = options.iterationsUpperBound()
        private set

    /**
     * If automatic adjustment of iteration number is disabled,
     * this is the number of planned iterations, otherwise null.
     */
    val iterationsStrictBound: Int? = options.iterationsStrictBound()

    /**
     * Current invocation number within current iteration.
     */
    var invocation: Int = 0
        private set

    /**
     * Number of invocations per iteration.
     * If automatic adjustment of invocations number is enabled, this variable can change during testing.
     */
    var invocationsBound = options.invocationsPerIteration
        private set

    private val originalMode        = options.mode

    private val adjustIterations    = options.adjustIterations
    private val adjustInvocations   = options.adjustInvocations

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

    fun shouldDoNextInvocation(): Boolean =
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

    fun <T> measureInvocationTime(block: () -> T): T {
        val start = System.currentTimeMillis()
        return block().also {
            val elapsed = System.currentTimeMillis() - start
            runningTime += elapsed
            invocationsRunningTime[invocationIndex] = elapsed
            if (++invocation == ADJUSTMENT_THRESHOLD) {
                adjustBounds()
                invocationsRunningTime.fill(0)
            }
        }
    }

    private fun adjustBounds() {
        // return if dynamic adjustment is disabled
        if (!adjustIterations && !adjustInvocations)
            return

        // first handle lower bound, make sure we are on schedule
        if (iteration < iterationsLowerBound) {
            // if invocation number adjustment is disabled there is nothing we can do
            if (!adjustInvocations)
                return
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
        if (adjustInvocations && timeEstimate / remainingTime > INVOCATIONS_FACTOR) {
            invocationsBound = max(INVOCATIONS_LOWER_BOUND, invocationsBound / INVOCATIONS_FACTOR)
            return
        }
        // if we over-perform by and order of magnitude, increase the number of invocations per iteration
        if (adjustInvocations && remainingTime / timeEstimate > INVOCATIONS_FACTOR) {
            invocationsBound = min(INVOCATIONS_UPPER_BOUND, invocationsBound * INVOCATIONS_FACTOR)
            return
        }

        // if iteration number adjustment is disabled there is nothing else we can do
        if (!adjustIterations)
            return

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
        private fun LincheckOptionsImpl.iterationsLowerBound() =
            if (adjustIterations)
                // if automatic adjustment is enabled, we definitely should run custom scenarios,
                // other scenarios only if there will be time left
                customScenarios.size
            else
                // if automatic adjustment is disabled, we have should perform
                // exactly the following number of iterations (as prescribed by the user)
                customScenarios.size + iterations

        private fun LincheckOptionsImpl.iterationsUpperBound() =
            if (adjustIterations)
                max(iterationsLowerBound(), iterations)
            else
                // if automatic adjustment is disabled, upper bound is equal to lower bound,
                // as we should perform the exact number of iterations
                iterationsLowerBound()

        private fun LincheckOptionsImpl.iterationsStrictBound() =
            if (!adjustIterations) iterationsLowerBound() else null

        // number of iterations added/subtracted when we over- or under-perform the plan
        private const val ITERATIONS_DELTA = 5

        // factor of invocations multiplied/divided by when we over- or under-perform the plan
        // by an order of magnitude
        private const val INVOCATIONS_FACTOR = 5

        // lower bound of invocations allocated by iteration
        private const val INVOCATIONS_LOWER_BOUND = 1_000

        // upper bound of invocations allocated by iteration
        private const val INVOCATIONS_UPPER_BOUND = 100_000

        // number of invocations performed between dynamic parameter adjustments
        private const val ADJUSTMENT_THRESHOLD = 100

        // in hybrid mode: testing progress threshold (in %) after which strategy switch
        //   from Stress to ModelChecking strategy occurs
        private const val STRATEGY_SWITCH_THRESHOLD = 25
    }

}