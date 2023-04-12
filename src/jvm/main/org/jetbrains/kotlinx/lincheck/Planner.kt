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
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import kotlin.math.*

interface IterationPlanner {
    fun shouldDoNextIteration(): Boolean

    fun iterationStart()

    fun iterationEnd()
}

interface InvocationPlanner {
    fun shouldDoNextInvocation(): Boolean

    fun invocationStart()

    fun invocationEnd()
}

inline fun IterationPlanner.trackIteration(block: () -> LincheckFailure?): LincheckFailure? {
    iterationStart()
    try {
        return block()
    } finally {
        iterationEnd()
    }
}

inline fun InvocationPlanner.trackInvocation(block: () -> InvocationResult): InvocationResult {
    invocationStart()
    try {
        return block()
    } finally {
        invocationEnd()
    }
}

internal class FixedInvocationPlanner(invocationsPerIteration: Int) : InvocationPlanner {
    private var remainingInvocations = invocationsPerIteration

    override fun shouldDoNextInvocation() = remainingInvocations > 0

    override fun invocationStart() {}

    override fun invocationEnd() {
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
internal class AdaptivePlanner ( // TODO: constructor with seconds
    /**
     * Total amount of time in milliseconds allocated to testing.
     */
    val testingTimeMs: Long,
    /**
     * A strict bound on the number of iterations that should be definitely performed.
     * If negative (by default), then adaptive iterations planning is used.
     */
    val iterationsStrictBound: Int = -1,
) : IterationPlanner, InvocationPlanner {
    /**
     * Total amount of time already spent on testing.
     */
    var runningTime: Long = 0
        private set

    /**
     * Remaining amount of time for testing.
     */
    val remainingTimeMs: Long
        get() = testingTimeMs - runningTime

    /**
     * Current iteration number.
      */
    var iteration: Int = 0
        private set

    val adaptiveIterationsBound: Boolean =
        iterationsStrictBound >= 0

    /**
     * Upper bound on the number of iterations.
     * Adjusted automatically after each iteration.
     */
    private var iterationsBound = if (adaptiveIterationsBound)
        INITIAL_ITERATIONS_BOUND
    else
        iterationsStrictBound

    /**
     * Number of remaining iterations, calculated based on current iterations bound.
     */
    private val remainingIterations: Int
        get() = iterationsBound - iteration

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
     * Upper bound on the number of invocations per iteration.
     * Adjusted automatically during each iteration.
     */
    private var invocationsBound = INITIAL_INVOCATIONS_BOUND

    // an array keeping running time of all iterations
    private val _iterationsRunningTime = mutableListOf<Long>()

    // and array keeping number of invocations executed for each iteration
    private val _iterationsInvocationCount = mutableListOf<Int>()

    // time limit allocated to current iteration
    private var currentIterationTimeBound = -1L

    private val currentIterationRemainingTime: Long
        get() = run {
            check(currentIterationTimeBound >= 0)
            currentIterationTimeBound - iterationsRunningTime[iteration]
        }

    // an array keeping running time of last N invocations
    private val invocationsRunningTime =
        LongArray(ADJUSTMENT_THRESHOLD) { 0 }

    private val invocationIndex: Int
        get() = invocation % ADJUSTMENT_THRESHOLD

    private val averageInvocationTime: Double
        get() = invocationsRunningTime.average()

    private var lastInvocationStartTime = -1L // TODO add `Nanos` suffix

    override fun shouldDoNextIteration(): Boolean =
        (remainingTimeMs > 0) && (iteration < iterationsBound)

    override fun shouldDoNextInvocation(): Boolean =
        (remainingTimeMs > 0) && (invocation < invocationsBound)

    override fun iterationStart() {
        _iterationsRunningTime.add(0)
        _iterationsInvocationCount.add(0)
        currentIterationTimeBound = remainingTimeMs / remainingIterations
    }

    override fun iterationEnd() {
        invocationsRunningTime.fill(0)
        iteration += 1
        invocation = 0
        if (adaptiveIterationsBound) {
            adjustIterationsBound()
        }
    }

    override fun invocationStart() {
        // TODO: Use system.nanoTime
        lastInvocationStartTime = System.currentTimeMillis()
    }

    override fun invocationEnd() {
        check(lastInvocationStartTime >= 0)
        val elapsed = System.currentTimeMillis() - lastInvocationStartTime
        runningTime += elapsed
        invocationsRunningTime[invocationIndex] = elapsed
        _iterationsRunningTime[iteration] += elapsed
        _iterationsInvocationCount[iteration] += 1
        if (++invocation % ADJUSTMENT_THRESHOLD == 0) {
            adjustInvocationBound()
            invocationsRunningTime.fill(0)
        }
    }

    private fun estimateRemainingTime(bound: Int) =
        (bound - iteration) * iterationsRunningTime[iteration]

    private fun adjustIterationsBound() {
        val estimate = estimateRemainingTime(iterationsBound)
        // if we over-perform, try to increase iterations bound
        if (estimate < remainingTimeMs) {
            val newIterationsBound = iterationsBound + ITERATIONS_DELTA
            // if with the larger bound we still over-perform, then increase the bound
            if (estimateRemainingTime(newIterationsBound) < remainingTimeMs) {
                iterationsBound = newIterationsBound
            }
        }
        // if we under-perform, then decrease iterations bound
        if (estimate > remainingTimeMs) {
            val delay = (estimate - remainingTimeMs).toDouble()
            val delayingIterations = floor(delay / iterationsRunningTime[iteration]).toInt()
            // remove the iterations we are unlikely to have time to do
            iterationsBound -= delayingIterations
        }
    }

    private fun estimateRemainingIterationTime(bound: Int) =
        (bound - invocation) * averageInvocationTime

    private fun adjustInvocationBound() {
        val estimate = estimateRemainingIterationTime(invocationsBound)
        // if we over-perform, try to increase invocations bound
        if (estimate < currentIterationRemainingTime && invocationsBound < INVOCATIONS_UPPER_BOUND) {
            val newInvocationsBound = min(invocationsBound * INVOCATIONS_FACTOR, INVOCATIONS_UPPER_BOUND)
            // if with the larger bound we still over-perform, then increase
            if (estimateRemainingIterationTime(newInvocationsBound) < currentIterationRemainingTime) {
                invocationsBound = newInvocationsBound
            }
        }
        // if we under-perform, then decrease invocations bound
        if (estimate > currentIterationRemainingTime && invocationsBound > INVOCATIONS_LOWER_BOUND) {
            invocationsBound = max(invocationsBound / INVOCATIONS_FACTOR, INVOCATIONS_LOWER_BOUND)
        }
    }

    companion object {
        // initial iterations upper bound
        private const val INITIAL_ITERATIONS_BOUND = 30

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
        private const val INVOCATIONS_UPPER_BOUND = 30_000

        // number of invocations performed between dynamic parameter adjustments
        private const val ADJUSTMENT_THRESHOLD = 100
    }

}