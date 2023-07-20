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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import kotlin.math.*

interface Planner {
    val scenarios: Sequence<ExecutionScenario>
    val iterationsPlanner: IterationsPlanner
    val invocationsPlanner: InvocationsPlanner
}

interface IterationsPlanner {
    fun shouldDoNextIteration(iteration: Int): Boolean
}

interface InvocationsPlanner {
    fun shouldDoNextInvocation(invocation: Int): Boolean
}

fun Planner.runIterations(
    tracker: RunTracker? = null,
    factory: (ExecutionScenario) -> Pair<Strategy, Verifier>,
): LincheckFailure? {
    scenarios.forEachIndexed { i, scenario ->
        if (!iterationsPlanner.shouldDoNextIteration(i))
            return null
        tracker.trackIteration(i, scenario) {
            val (strategy, verifier) = factory(scenario)
            strategy.use {
                var invocation = 0
                while (invocationsPlanner.shouldDoNextInvocation(invocation)) {
                    if (!strategy.nextInvocation()) {
                        return@trackIteration null
                    }
                    strategy.initializeInvocation()
                    tracker.trackInvocation(invocation) {
                        strategy.runInvocation(verifier)
                    }?.let {
                        return@trackIteration it
                    }
                    invocation++
                }
            }
            return@trackIteration null
        }?.let {
            return it
        }
    }
    return null
}

internal class CustomScenariosPlanner(
    val scenariosOptions: List<CustomScenarioOptions>,
) : Planner, IterationsPlanner {

    override val scenarios = scenariosOptions.asSequence().map { it.scenario }

    override val iterationsPlanner = this

    override var invocationsPlanner = FixedInvocationsPlanner(0)
        private set

    override fun shouldDoNextIteration(iteration: Int): Boolean {
        invocationsPlanner = FixedInvocationsPlanner(scenariosOptions[iteration].invocations)
        return iteration < scenariosOptions.size
    }

}

internal class RandomScenariosAdaptivePlanner(
    mode: LincheckMode,
    testingTimeMs: Long,
    val minThreads: Int,
    val maxThreads: Int,
    val minOperations: Int,
    val maxOperations: Int,
    val generateBeforeAndAfterParts: Boolean,
    scenarioGenerator: ExecutionGenerator,
    statisticsTracker: StatisticsTracker,
) : Planner {
    private val planner = AdaptivePlanner(mode, testingTimeMs, statisticsTracker)
    override val iterationsPlanner = planner
    override val invocationsPlanner = planner

    private val configurations: List<Pair<Int, Int>> = run {
        (minThreads .. maxThreads).flatMap { threads ->
            (minOperations .. maxOperations).flatMap { operations ->
                listOf(threads to operations)
            }
        }
    }

    override val scenarios = sequence {
        while (true) {
            val n = round(planner.testingProgress * configurations.size).toInt()
                .coerceAtLeast(0)
                .coerceAtMost(configurations.size - 1)
            val (threads, operations) = configurations[n]
            yield(scenarioGenerator.nextExecution(threads, operations,
                if (generateBeforeAndAfterParts) operations else 0,
                if (generateBeforeAndAfterParts) operations else 0,
            ))
        }
    }
}

internal class FixedInvocationsPlanner(val totalInvocations: Int) : InvocationsPlanner {
    override fun shouldDoNextInvocation(invocation: Int) =
        invocation < totalInvocations
}

/**
 * The class for planning the work distribution during one run.
 * In particular, it is responsible for the following activities:
 * - measuring the time of invocations run;
 * - keep the track of deadlines and remaining time;
 * - adaptively adjusting number of test scenarios and invocations allocated per scenario.
 */
internal class AdaptivePlanner(
    /**
     * Testing mode. It is used to calculate upper/lower bounds on the number of invocations.
     */
    mode: LincheckMode,
    /**
     * Total amount of time in milliseconds allocated for testing.
     */
    testingTimeMs: Long,
    /**
     * Statistics tracker.
     */
    val statisticsTracker: StatisticsTracker,
) : IterationsPlanner, InvocationsPlanner {

    /**
     * Total amount of time in nanoseconds allocated for testing.
     */
    var testingTimeNano: Long = testingTimeMs * 1_000_000
        private set

    /**
     * Remaining amount of time for testing.
     */
    val remainingTimeNano: Long
        get() = (testingTimeNano - statisticsTracker.totalRunningTimeNano).coerceAtLeast(0)

    /**
     * Testing progress: floating-point number in range [0.0 .. 1.0],
     * representing a fraction of spent testing time.
     */
    val testingProgress: Double
        get() = (statisticsTracker.totalRunningTimeNano / testingTimeNano.toDouble()).coerceAtMost(1.0)

    /**
     * Admissible time delay (in nano-seconds) for last iteration to finish,
     * even if it exceeds [remainingTimeNano].
     */
    private val admissibleErrorTimeNano = TIME_ERROR_MARGIN_NANO / 2

    /**
     * Bound on the number of iterations.
     * Adjusted automatically after each iteration.
     */
    private var iterationsBound = INITIAL_ITERATIONS_BOUND

    /**
     * Bound on the number of invocations allocated to current iteration.
     * Adjusted automatically after each iteration.
     */
    private var invocationsBound = INITIAL_INVOCATIONS_BOUND

    /**
     * Lower bound of invocations allocated per iteration.
     */
    private val invocationsLowerBound = INVOCATIONS_LOWER_BOUND

    /**
     * Upper bound of invocations allocated per iteration.
     */
    private val invocationsUpperBound = when (mode) {
        LincheckMode.Stress         -> STRESS_INVOCATIONS_UPPER_BOUND
        LincheckMode.ModelChecking  -> MODEL_CHECKING_INVOCATIONS_UPPER_BOUND
        else -> throw IllegalArgumentException()
    }

    private var currentIterationUpperTimeNano = Long.MAX_VALUE

    override fun shouldDoNextIteration(iteration: Int): Boolean {
        check(iteration == statisticsTracker.iteration + 1)
        if (iteration >= WARM_UP_ITERATIONS.coerceAtLeast(1)) {
            adjustBounds(
                // set number of completed iterations
                performedIterations = statisticsTracker.iteration + 1,
                // total number of performed invocations, excluding warm-up invocations
                performedInvocations = statisticsTracker.invocationsCount,
                // remaining time; for simplicity we do not estimate and subtract warm-up time
                remainingTimeNano = remainingTimeNano,
                // as an estimated average invocation time we took average invocation time on previous iteration,
                // excluding warm-up invocations
                averageInvocationTimeNano = with(statisticsTracker.iterationsStatistics[statisticsTracker.iteration]) {
                    if (invocationsCount > 0)
                        averageInvocationTimeNano
                    else
                        // in case when no iterations, except warm-up iterations, were performed,
                        // take average time on all iterations (including warm-up) as an estimate
                        statisticsTracker.totalRunningTimeNano.toDouble() / statisticsTracker.totalInvocationsCount
                },
            )
        }
        return (iteration < iterationsBound) && (remainingTimeNano > 0)
    }

    override fun shouldDoNextInvocation(invocation: Int): Boolean {
        check(invocation == statisticsTracker.invocation + 1)
        if (invocation == 0) {
            statisticsTracker.iterationWarmUpStart(statisticsTracker.iteration)
        }
        if (invocation == WARM_UP_INVOCATIONS_COUNT) {
            statisticsTracker.iterationWarmUpEnd(statisticsTracker.iteration)
        }
        if (statisticsTracker.totalRunningTimeNano > testingTimeNano + admissibleErrorTimeNano) {
            return false
        }
        if (invocation < WARM_UP_INVOCATIONS_COUNT) {
            return true
        }
        if (statisticsTracker.currentIterationRunningTimeNano > currentIterationUpperTimeNano) {
            return false
        }
        return (invocation < invocationsBound + statisticsTracker.currentIterationWarmUpInvocationsCount)
    }

    /*
     * Adjustment of remaining iteration and invocation bounds.
     * We aim to maintain the following ratio between number of iterations and invocations:
     *     C = M / N
     * where
     *   - C is a ratio constant,
     *   - N is a number of iterations,
     *   - M is a number of invocations.
     *
     * We call this function after each iteration in order to adjust N and M to ensure that the desired ratio is preserved.
     * We estimate average invocation time based on statistics, and then divide
     * remaining time to average invocation time to compute total remaining number of invocations.
     * Then, given that total invocations count T is can be computed as follows:
     *     T = N * M
     * and we have that
     *     N = M / C
     * we derive that number of invocations should be adjusted as follows:
     *     M = sqrt(T * C)
     *
     * If after these calculations, there is significant outrun or delay
     * then we manually add/remove few iterations to better fit into the time constraints
     * (this can happen, for example, when we hit invocation max/min bounds).
     *
     * TODO: update doc !!!
     */
    private fun adjustBounds(
        performedIterations: Int,
        performedInvocations: Int,
        averageInvocationTimeNano: Double,
        remainingTimeNano: Long
    ) {
        require(averageInvocationTimeNano > 0)
        if (remainingTimeNano <= 0)
            return
        // estimate number of remaining invocations
        val remainingInvocations = floor(remainingTimeNano / averageInvocationTimeNano)
        // shorter name for invocations to iterations ratio constant
        val ratio = INVOCATIONS_TO_ITERATIONS_RATIO.toDouble()
        // calculate remaining iterations bound
        var remainingIterations = solveQuadraticEquation(
            a = ratio,
            b = 2 * ratio * performedIterations,
            c = ratio * performedIterations * performedIterations - (performedInvocations + remainingInvocations),
            default = 0.0
        ).let { round(it).toInt() }.coerceAtLeast(0)

        // if there are some iterations left, derive invocations per iteration bound by
        // dividing total remaining invocations number to the number of remaining iterations
        if (remainingIterations > 0) {
            invocationsBound = round(remainingInvocations / remainingIterations)
                .toInt()
                .roundUpTo(INVOCATIONS_FACTOR)
                .coerceAtLeast(invocationsLowerBound)
                .coerceAtMost(invocationsUpperBound)
        }

        // if there is no remaining iterations, but there is still some time left
        // (more time than admissible error),
        // we still can try to perform one more iteration ---
        // in case of overdue it will be just aborted when the time is up;
        // this additional iteration helps us to prevent the case when we finish earlier and
        // do not use all allocated testing time;
        // we allocate all remaining invocations to this last iteration;
        // however, because in some rare cases even single invocation can take significant time
        // and thus surpass the deadline, we still perform additional check
        // to see if there enough time to perform at least X of additional invocations.
        if (remainingIterations == 0 &&
            remainingTimeNano > admissibleErrorTimeNano &&
            averageInvocationTimeNano * invocationsLowerBound < remainingTimeNano) {
            invocationsBound = remainingInvocations.toInt()
            remainingIterations += 1
        }

        // finally, set the iterations bound
        iterationsBound = performedIterations + remainingIterations

        // calculate upper bound on running time for the next iteration
        currentIterationUpperTimeNano = if (remainingIterations > 0)
                round((remainingTimeNano * PLANNED_ITERATION_TIME_ERROR_FACTOR) / remainingIterations).toLong()
            else 0L
    }

    private fun solveQuadraticEquation(a: Double, b: Double, c: Double, default: Double): Double {
        val d = (b * b - 4 * a * c)
        return if (d >= 0)
            max(
                (-b + sqrt(d)) / (2 * a),
                (-b - sqrt(d)) / (2 * a),
            )
        else default
    }

    companion object {
        // initial iterations upper bound
        private const val INITIAL_ITERATIONS_BOUND = 30

        // number of iterations added/subtracted when we over- or under-perform the plan
        private const val ITERATIONS_DELTA = 5

        // initial number of invocations
        private const val INITIAL_INVOCATIONS_BOUND = 500

        internal const val WARM_UP_ITERATIONS = 1

        // number of warm-up invocations
        private const val WARM_UP_INVOCATIONS_COUNT = 10

        // number of invocations should be divisible to this constant,
        // that is we ensure number of invocations is always rounded up to this constant
        internal const val INVOCATIONS_FACTOR = 100

        internal const val INVOCATIONS_TO_ITERATIONS_RATIO = 100

        internal const val INVOCATIONS_LOWER_BOUND = 100
        internal const val STRESS_INVOCATIONS_UPPER_BOUND = 1_000_000
        internal const val MODEL_CHECKING_INVOCATIONS_UPPER_BOUND = 20_000

        private const val PLANNED_ITERATION_TIME_ERROR_FACTOR = 2.0

        // error up to 1.5 sec --- we can try to decrease the error in the future
        internal const val TIME_ERROR_MARGIN_NANO = 1_500_000_000
    }

}

private fun Double.roundUpTo(c: Double) = round(this / c) * c
private fun Int.roundUpTo(c: Int) = toDouble().roundUpTo(c.toDouble()).toInt()

private fun Double.ceilUpTo(c: Double) = ceil(this / c) * c
private fun Int.ceilUpTo(c: Int) = toDouble().ceilUpTo(c.toDouble()).toInt()