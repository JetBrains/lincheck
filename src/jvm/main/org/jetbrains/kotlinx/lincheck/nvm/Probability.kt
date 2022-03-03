/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.BinarySearchSolver
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

/**
 * This model helps to assign crash probabilities to the crash points so that the frequency of
 * crashes at each point is more or less equal.
 *
 * The model is based on the length of the actors involved, therefore [Statistics] is used.
 *
 * @see <a href="https://spb.hse.ru/mirror/pubs/share/480761026.pdf#page=23"> Theoretical reasoning (in Russian) </a>
 * The notation used is consistent with the theoretical reasoning.
 */
abstract class ProbabilityModel(protected val statistics: Statistics, protected val maxCrashes: Int) {
    /** Probability of crash in the first crash point in thread [threadId] actor [actorId]. */
    protected abstract fun actorProbability(threadId: Int, actorId: Int): Double

    /** Is one more crash allowed with respect of [maxCrashes]. */
    internal abstract fun isCrashAllowed(crashesNumber: Int): Boolean

    /**
     * Probability of crash in the [j]-th crash point on the thread [threadId].
     */
    internal fun crashPointProbability(threadId: Int, j: Int): Double {
        val actorId = statistics.currentActor(threadId)
        val c = actorProbability(threadId, actorId)
        return c / (1 - j * c)
    }
}

/** Probability model with no crashes allowed. */
internal class NoCrashesProbabilityModel(statistics: Statistics, maxCrashes: Int) : ProbabilityModel(statistics, maxCrashes) {
    override fun actorProbability(threadId: Int, actorId: Int) = 0.0
    override fun isCrashAllowed(crashesNumber: Int) = false
}

/**
 * Probability model for durable linearizability execution model with at most [maxCrashes] crashes.
 *
 * Note that the original theoretical reasoning does not consider multiply threads, so perfect uniformity is not guarantied for multiply threads.
 * In this model threads are considered independently.
 */
internal class DurableProbabilityModel(statistics: Statistics, maxCrashes: Int) : ProbabilityModel(statistics, maxCrashes) {
    /** Probability of crash on the first crash point in each actor. */
    private val c: DoubleArray

    /** W_i is the probability of less than [maxCrashes] crashes occurred before i-th actor. */
    private val W: Array<DoubleArray>

    init {
        val length = statistics.estimatedActorsLength
        c = DoubleArray(length.size) { i -> findC(length[i], maxCrashes) }
        W = Array(length.size) { i -> calculateW(length[i], c[i], maxCrashes) }
    }

    override fun actorProbability(threadId: Int, actorId: Int) =
        if (actorId == 0) c[threadId] else c[threadId] / W[threadId][actorId - 1]

    override fun isCrashAllowed(crashesNumber: Int) = crashesNumber < maxCrashes

    /**
     * C can be found via a binary search.
     * @param length actors' length
     * @param M maximum number of crashes
     */
    private fun findC(length: DoubleArray, M: Int): Double {
        val n = length.size
        val N = length.sum()
        if (N <= 0.0) return 0.0
        val w0 = DoubleArray(n + 1) // allocate buffer once
        val w1 = DoubleArray(n + 1) // allocate buffer once
        val s = DoubleArray(n) // allocate buffer once
        val solver = BinarySearchSolver { c ->
            val _W = calculateW(length, c, M, w0, w1, s)
            c - (0 until n).minOf { i -> (if (i == 0) 1.0 else _W[i - 1]) / length[i] }
        }
        val mc = min(1.0 / length.maxOrNull()!!, M / N) // maximum value of c allowed
        return solver.solve(0.0, mc, 1e-9)
    }

    /**
     * Calculate W_i with dynamic programming in case of known [c].
     * @param length actors' length
     * @param _w0 buffer for internal calculations
     * @param w1 buffer for internal calculations
     * @param _W the answer buffer
     */
    private fun calculateW(
        length: DoubleArray,
        c: Double,
        maxCrashes: Int,
        _w0: DoubleArray = DoubleArray(length.size + 1),
        w1: DoubleArray = DoubleArray(length.size + 1),
        _W: DoubleArray = DoubleArray(length.size)
    ): DoubleArray {
        var w0 = _w0
        val n = length.size
        if (n == 0) return _W

        // w_i[k] - probability of exactly k crashes occurred at actors up to i
        // Implementation note: here we use only two arrays w0 and w1 instead of n arrays
        // as w_{i - 1} is only used to calculate w_i

        w0.fill(0.0, 2)
        w0[0] = 1 - c * length[0]
        w0[1] = c * length[0]

        _W[0] = w0[0] + if (maxCrashes == 1) 0.0 else w0[1]
        for (i in 1 until n) {
            val y = c * length[i] / _W[i - 1]

            w1[0] = (1 - y) * w0[0]
            for (k in 1..n) {
                w1[k] = y * w0[k - 1] + (1 - y) * w0[k]
            }
            for (k in 0 until maxCrashes) {
                _W[i] += w1[k]
            }
            w0 = w1
        }
        return _W
    }
}

/**
 * Probability model for detectable execution model.
 *
 * Note that the original theoretical reasoning does not consider multiply threads, so perfect uniformity is not guarantied.
 * In this model threads are considered independently.
 *
 * Note that the original theoretical reasoning supposes infinite possible crashes allowed, which is meaningless in practice.
 * Therefore, maximum number of crashes is limited, which may break uniformity a little.
 */
internal class DetectableExecutionProbabilityModel(statistics: Statistics, maxCrashes: Int) : ProbabilityModel(statistics, maxCrashes) {
    private val c: Double

    init {
        val n = statistics.totalLength
        val e = maxCrashes.toDouble()
        c = if (n > 0) e / n else 0.0
    }

    override fun actorProbability(threadId: Int, actorId: Int) = c / (1 + c * statistics.actorLength(threadId, actorId))
    override fun isCrashAllowed(crashesNumber: Int) = crashesNumber < 2 * maxCrashes
}

/**
 * This class calculates the execution statistics used by [ProbabilityModel]s.
 *
 * Namely, the target statistic is the estimation of actors' length involved into the current scenario.
 * This is done by calculating the number of crash points between [onEnterActorBody] and [onExitActorBody] methods invocations.
 * Note that crashed actors must not be involved into actor's length estimation,
 * so all calculations are performed locally until [onExitActorBody] is call, then we know that actor has finished successfully.
 */
class Statistics(scenario: ExecutionScenario) {
    private val currentActor = IntArray(scenario.threads + 2)
    private val localCrashPoints = IntArray(scenario.threads + 2)
    private val crashPoints = Array(scenario.threads + 2) { threadId -> IntArray(scenario[threadId].size) }
    private val invocations = Array(scenario.threads + 2) { threadId -> IntArray(scenario[threadId].size) }
    val estimatedActorsLength = Array(scenario.threads + 2) { threadId -> DoubleArray(scenario[threadId].size) }
    private var maxLength = 0.0
    internal var totalLength = 0.0
        private set

    internal fun onEnterActorBody(threadId: Int, actorId: Int) {
        localCrashPoints[threadId] = 0
        currentActor[threadId] = actorId
    }

    internal fun onExitActorBody(threadId: Int, actorId: Int) {
        crashPoints[threadId][actorId] += localCrashPoints[threadId]
        invocations[threadId][actorId]++
        localCrashPoints[threadId] = 0
    }

    internal fun onNewInvocation() {
        maxLength = 0.0
        totalLength = 0.0
        for (threadId in estimatedActorsLength.indices) {
            for (actorId in estimatedActorsLength[threadId].indices) {
                if (invocations[threadId][actorId] == 0) continue
                val length = crashPoints[threadId][actorId].toDouble() / invocations[threadId][actorId]
                estimatedActorsLength[threadId][actorId] = length
                maxLength = max(maxLength, length)
                totalLength += length
            }
        }
    }

    internal fun onCrashPoint(threadId: Int) = localCrashPoints[threadId]++
    internal fun actorLength(threadId: Int, actorId: Int) = estimatedActorsLength[threadId][actorId]
    internal fun currentActor(threadId: Int) = currentActor[threadId]
}

private const val RANDOM_FLUSH_PROBABILITY = 0.05

/**
 * This class is an entry point to all random-related methods that are needed for NVM emulation.
 *
 * During execution crashes may happen at random in stress mode, whether to perform them is defined by [shouldCrash] and [shouldSystemCrash] methods.
 * Also, flush may happen at random, so [shouldFlush] method can be used.
 * Note that in model checking mode the random seed is changed deterministically with [resetRandom] method.
 */
class Probability(scenario: ExecutionScenario, recoverModel: RecoverabilityModel, private val state: NVMState) {
    private val statistics = Statistics(scenario)
    private val randomSystemCrashProbability = recoverModel.systemCrashProbability()

    @Volatile
    private var randomGetter: () -> Random = { ThreadLocalRandom.current() }

    private val iterationRandom = Random(LinChecker.iterationId.get().toLong())
    private val modelCheckingRandom = Random(42)
    private val expectedCrashes = LinChecker.crashesMinimization.get() ?: recoverModel.defaultExpectedCrashes()

    @Volatile
    private lateinit var model: ProbabilityModel

    internal fun shouldFlush() = bernoulli(RANDOM_FLUSH_PROBABILITY)

    /** Whether to perform a system crash if [shouldCrash] returned true. */
    internal fun shouldSystemCrash() = bernoulli(randomSystemCrashProbability)
    internal fun shouldCrash(): Boolean {
        if (!state.crashesEnabled) return false
        val threadId = state.currentThreadId()
        val crashPointId = statistics.onCrashPoint(threadId)
        return moreCrashesPermitted() && bernoulli(model.crashPointProbability(threadId, crashPointId))
    }

    /** This method should be called on every new invocation to recalculate the values. */
    internal fun setNewInvocation(recoverModel: RecoverabilityModel) {
        statistics.onNewInvocation()
        model = recoverModel.createProbabilityModel(statistics, expectedCrashes)
    }

    private fun moreCrashesPermitted() = model.isCrashAllowed(occurredCrashes())
    private fun occurredCrashes() = if (randomSystemCrashProbability < 1.0) {
        state.crashesCount
    } else {
        state.maxCrashesCountPerThread
    }

    private fun bernoulli(probability: Double) = randomGetter().nextDouble() < probability

    /**
     * Use this method to set the random seed to make the execution deterministic in model checking mode.
     */
    internal fun resetRandom(seed: Int) {
        modelCheckingRandom.setSeed(seed.toLong())
        randomGetter = { modelCheckingRandom }
    }

    internal fun generateSeed(): Int = iterationRandom.nextInt()

    internal fun onEnterActorBody(threadId: Int, actorId: Int) = statistics.onEnterActorBody(threadId, actorId)
    internal fun onExitActorBody(threadId: Int, actorId: Int) = statistics.onExitActorBody(threadId, actorId)
}
