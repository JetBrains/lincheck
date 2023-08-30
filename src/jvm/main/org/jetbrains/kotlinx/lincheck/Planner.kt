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
import org.jetbrains.kotlinx.lincheck.runner.SpinCycleFoundAndReplayRequired
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import kotlin.math.*

internal interface Planner {
    val scenarios: Sequence<ExecutionScenario>
    val iterationsPlanner: IterationsPlanner
    fun invocationsPlanner(iteration: Int): InvocationsPlanner
}

internal interface IterationsPlanner {
    fun shouldDoNextIteration(iteration: Int): Boolean
    fun iterationOptions(iteration: Int): IterationOptions
}

internal interface InvocationsPlanner {
    fun shouldDoNextInvocation(invocation: Int): Boolean
}

data class IterationOptions(
    val mode: LincheckMode,
    val invocationsBound: Int,
    val warmUpInvocationsCount: Int,
)

internal typealias StrategyFactory = (ExecutionScenario, IterationOptions) -> Strategy

internal fun Planner.runIterations(
    verifier: Verifier,
    tracker: RunTracker? = null,
    factory: StrategyFactory,
): LincheckFailure? {
    scenarios.forEachIndexed { iteration, scenario ->
        if (!iterationsPlanner.shouldDoNextIteration(iteration))
            return null
        val options = iterationsPlanner.iterationOptions(iteration)
        val invocationsPlanner = invocationsPlanner(iteration)
        tracker.trackIteration(iteration, scenario, options) {
            factory(scenario, options).use { strategy ->
                var invocation = 0
                var spinning = false
                while (invocationsPlanner.shouldDoNextInvocation(invocation)) {
                    if (!spinning && !strategy.nextInvocation()) {
                        return@trackIteration null
                    }
                    spinning = false
                    strategy.initializeInvocation()
                    val failure = tracker.trackInvocation(invocation) {
                        val result = strategy.runInvocation()
                        spinning = (result is SpinCycleFoundAndReplayRequired)
                        strategy.verify(result, verifier)
                    }
                    if (failure != null)
                        return failure
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
    val mode: LincheckMode,
    scenariosOptions: List<CustomScenarioOptions>,
) : Planner {

    // if we are in hybrid mode, then each scenario should be run
    // both under Stress and ModelChecking strategies
    val scenariosOptions = scenariosOptions
        .flatMap { options ->
            val mode = options.mode ?: this.mode
            if (mode == LincheckMode.Hybrid) {
                val stressInvocations = (options.invocations * STRATEGY_SWITCH_THRESHOLD).toInt()
                val modelCheckingInvocations = options.invocations - stressInvocations
                listOf(
                    options.copy(mode = LincheckMode.Stress, invocations = stressInvocations),
                    options.copy(mode = LincheckMode.ModelChecking, invocations = modelCheckingInvocations),
                )
            } else {
                listOf(options.copy(mode = mode))
            }
        }

    override val scenarios =
        this.scenariosOptions.asSequence().map { it.scenario }

    override val iterationsPlanner =
        FixedIterationsPlanner(this.scenariosOptions.size) { iteration ->
            IterationOptions(
                mode = this.scenariosOptions[iteration].mode!!,
                invocationsBound = this.scenariosOptions[iteration].invocations,
                warmUpInvocationsCount = 0,
            )
        }

    override fun invocationsPlanner(iteration: Int) =
        FixedInvocationsPlanner(scenariosOptions[iteration].invocations)

}

internal data class RandomScenarioOptions(
    val minThreads: Int,
    val maxThreads: Int,
    val minOperations: Int,
    val maxOperations: Int,
    val generateBeforeAndAfterParts: Boolean,
)

internal class RandomScenariosFixedPlanner(
    val mode: LincheckMode,
    val iterations: Int,
    val invocationsPerIteration: Int,
    randomScenarioOptions: RandomScenarioOptions,
    scenarioGenerator: ExecutionGenerator,
) : Planner {

    override val scenarios: Sequence<ExecutionScenario> =
        scenarioGenerator.createSequence(randomScenarioOptions, ::testingProgress)

    override val iterationsPlanner: IterationsPlanner =
        FixedIterationsPlanner(iterations) { iteration ->
            IterationOptions(
                mode = iterationMode(iteration),
                invocationsBound = invocationsPerIteration,
                warmUpInvocationsCount = 0,
            )
        }

    override fun invocationsPlanner(iteration: Int): InvocationsPlanner =
        FixedInvocationsPlanner(invocationsPerIteration)

    private fun testingProgress(iteration: Int): Double =
        iteration.toDouble() / iterations

    private fun iterationMode(iteration: Int): LincheckMode = when {
        this.mode == LincheckMode.Hybrid && testingProgress(iteration) < STRATEGY_SWITCH_THRESHOLD ->
            LincheckMode.Stress
        this.mode == LincheckMode.Hybrid && testingProgress(iteration) >= STRATEGY_SWITCH_THRESHOLD ->
            LincheckMode.ModelChecking
        else ->
            this.mode
    }
}

internal class RandomScenariosAdaptivePlanner(
    mode: LincheckMode,
    testingTimeMs: Long,
    randomScenarioOptions: RandomScenarioOptions,
    scenarioGenerator: ExecutionGenerator,
    statisticsTracker: StatisticsTracker,
) : Planner {
    private val planner = AdaptivePlanner(mode, testingTimeMs, statisticsTracker)

    override val scenarios: Sequence<ExecutionScenario> =
        scenarioGenerator.createSequence(randomScenarioOptions) {
            planner.testingProgress
        }

    override val iterationsPlanner = planner

    override fun invocationsPlanner(iteration: Int) = planner

}

private fun ExecutionGenerator.createSequence(
    options: RandomScenarioOptions,
    testingProgress: (Int) -> Double,
): Sequence<ExecutionScenario> {
    val configurations: List<Pair<Int, Int>> =
        (options.minThreads .. options.maxThreads).flatMap { threads ->
            (options.minOperations .. options.maxOperations).flatMap { operations ->
                listOf(threads to operations)
            }
        }
    return sequence {
        var i = 0
        while (true) {
            val n = round(testingProgress(i++) * configurations.size).toInt()
                .coerceAtLeast(0)
                .coerceAtMost(configurations.size - 1)
            val (threads, operations) = configurations[n]
            yield(nextExecution(threads, operations,
                if (options.generateBeforeAndAfterParts) operations else 0,
                if (options.generateBeforeAndAfterParts) operations else 0,
            ))
        }
    }
}

internal class FixedIterationsPlanner(
    val totalIterations: Int,
    val iterationOptionsFactory: (Int) -> IterationOptions,
) : IterationsPlanner {
    override fun shouldDoNextIteration(iteration: Int): Boolean =
        iteration < totalIterations

    override fun iterationOptions(iteration: Int): IterationOptions =
        iterationOptionsFactory(iteration)
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
    val mode: LincheckMode,
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
     * Bound on the number of invocations allocated to current iteration (excluding warm-up invocations).
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
    private val invocationsUpperBound = when (currentMode) {
        LincheckMode.Stress         -> STRESS_INVOCATIONS_UPPER_BOUND
        LincheckMode.ModelChecking  -> MODEL_CHECKING_INVOCATIONS_UPPER_BOUND
        else -> throw IllegalArgumentException()
    }

    private var currentIterationUpperTimeNano = Long.MAX_VALUE

    private val currentMode: LincheckMode get() = when {
        mode == LincheckMode.Hybrid && (testingProgress < STRATEGY_SWITCH_THRESHOLD) ->
            LincheckMode.Stress
        mode == LincheckMode.Hybrid && (testingProgress >= STRATEGY_SWITCH_THRESHOLD) ->
            LincheckMode.ModelChecking
        else ->
            mode
    }

    override fun iterationOptions(iteration: Int) = IterationOptions(
        mode = currentMode,
        invocationsBound = invocationsBound,
        warmUpInvocationsCount = WARM_UP_INVOCATIONS_COUNT,
    )

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
        if (statisticsTracker.totalRunningTimeNano > testingTimeNano + admissibleErrorTimeNano)
            return false
        if (statisticsTracker.currentIterationRunningTimeNano > currentIterationUpperTimeNano)
            return false
        return (invocation < invocationsBound)
    }

    /*
     * Adjustment of remaining iteration and invocation bounds.
     * This function is called after each iteration in order to adjust iterations and invocations bounds.
     *
     * We aim to maintain the following ratio between number of iterations and invocations:
     *     C = A / I
     * where
     *   - C is a ratio constant,
     *   - I is a number of iterations,
     *   - A is a number of invocations per iteration.
     *
     * Note that the number of performed invocations may vary for different iterations,
     * thus for A we take average number of invocations per iteration.
     *     A = J / I
     * where
     *   - J is the total number of invocations performed on all iterations.
     *
     * Therefore, we derive the following equation:
     *     C = J / I^2
     *
     * The adjustment function takes as arguments:
     *   - K --- number of performed iterations so far;
     *   - P --- total number of performed invocations so far;
     *   - T --- estimated average invocation time;
     *   - R --- remaining time.
     * and it computes:
     *   - N --- number of remaining iterations;
     *   - M --- invocations bound for the next iteration.
     *
     * We have that:
     *   - I = K + N
     *   - J = P + M * N
     * Therefore, to find N and M, we have to solve the following system of equations:
     *
     *   (1) (P + M * N) / (K + N)^2 = C
     *   (2) M * N = R / T
     *
     * (note that R / T is the number of remaining invocations).
     *
     * By performing a rewrite M * N = R / T in the equation (1) and then applying other simplifications,
     * we arrive at the following quadratic equation for N:
     *
     *   (1') C * N^2 + 2CK * N + (C * K^2 - P - R / T) = 0
     *
     * This equation can have either 0, 1, or 2 solutions.
     * If it has 0 positive solutions, we take N = 0.
     * If it has 2 solutions, we take N to be the maximal one.
     *
     * Then using the found N we determine M using equation (2):
     *     M = R / (T * N).
     *
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

        // add warm-up invocations
        invocationsBound += WARM_UP_INVOCATIONS_COUNT

        // finally, set the iterations bound
        iterationsBound = performedIterations + remainingIterations

        // calculate upper bound on running time for the next iteration
        currentIterationUpperTimeNano = if (remainingIterations > 0)
                round((remainingTimeNano * PLANNED_ITERATION_TIME_ERROR_FACTOR) / remainingIterations).toLong()
            else 0L
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

// in hybrid mode: testing progress threshold (in %) after which strategy switch
//   from Stress to ModelChecking strategy occurs
private const val STRATEGY_SWITCH_THRESHOLD = 0.25

private fun solveQuadraticEquation(a: Double, b: Double, c: Double, default: Double): Double {
    val d = (b * b - 4 * a * c)
    return if (d >= 0)
        max(
            (-b + sqrt(d)) / (2 * a),
            (-b - sqrt(d)) / (2 * a),
        )
    else default
}

private fun Double.roundUpTo(c: Double) = round(this / c) * c
private fun Int.roundUpTo(c: Int) = toDouble().roundUpTo(c.toDouble()).toInt()

private fun Double.ceilUpTo(c: Double) = ceil(this / c) * c
private fun Int.ceilUpTo(c: Int) = toDouble().ceilUpTo(c.toDouble()).toInt()