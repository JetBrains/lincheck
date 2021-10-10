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

abstract class ProbabilityModel(protected val statistics: Statistics, protected val maxCrashes: Int) {
    protected abstract fun actorProbability(iThread: Int, iActor: Int): Double
    internal abstract fun isCrashAllowed(crashesNumber: Int): Boolean
    internal fun crashPointProbability(iThread: Int, j: Int): Double {
        val i = statistics.currentActor(iThread)
        val c = actorProbability(iThread, i)
        return c / (1 - j * c)
    }
}

internal class NoCrashesProbabilityModel(statistics: Statistics, maxCrashes: Int) : ProbabilityModel(statistics, maxCrashes) {
    override fun actorProbability(iThread: Int, iActor: Int) = 0.0
    override fun isCrashAllowed(crashesNumber: Int) = false
}

internal class DurableProbabilityModel(statistics: Statistics, maxCrashes: Int) : ProbabilityModel(statistics, maxCrashes) {
    private val c: DoubleArray
    private val s: Array<DoubleArray>

    init {
        val length = statistics.estimatedActorsLength
        c = DoubleArray(length.size) { i -> findC(length[i], maxCrashes) }
        s = Array(length.size) { i -> calculatePreviousCrashesProbabilities(length[i], c[i], maxCrashes) }
    }

    override fun actorProbability(iThread: Int, iActor: Int) =
        if (iActor == 0) c[iThread] else c[iThread] / s[iThread][iActor - 1]

    override fun isCrashAllowed(crashesNumber: Int) = crashesNumber < maxCrashes

    private fun findC(actors: DoubleArray, maxCrashes: Int): Double {
        val n = actors.size
        val total = actors.sum()
        if (total <= 0.0) return 0.0
        val w0 = DoubleArray(n + 1)
        val w1 = DoubleArray(n + 1)
        val s = DoubleArray(n)
        val solver = BinarySearchSolver { c ->
            val p = calculatePreviousCrashesProbabilities(actors, c, maxCrashes, w0, w1, s)
            c - (0 until n).minOf { i -> (if (i == 0) 1.0 else p[i - 1]) / actors[i] }
        }
        val mc = min(1.0 / actors.maxOrNull()!!, maxCrashes.toDouble() / total)
        return solver.solve(0.0, mc, 1e-9)
    }

    private fun calculatePreviousCrashesProbabilities(
        actors: DoubleArray,
        c: Double,
        maxCrashes: Int,
        _w0: DoubleArray = DoubleArray(actors.size + 1),
        w1: DoubleArray = DoubleArray(actors.size + 1),
        s: DoubleArray = DoubleArray(actors.size)
    ): DoubleArray {
        var w0 = _w0
        val n = actors.size
        if (n == 0) return s
        w0.fill(0.0, 2)
        w0[0] = 1 - c * actors[0]; w0[1] = c * actors[0]
        s[0] = w0[0] + if (maxCrashes == 1) 0.0 else w0[1]
        for (i in 1 until n) {
            val z = c * actors[i] / s[i - 1]
            w1[0] = (1 - z) * w0[0]
            for (k in 1..n) {
                w1[k] = z * w0[k - 1] + (1 - z) * w0[k]
            }
            for (k in 0 until maxCrashes) {
                s[i] += w1[k]
            }
            w0 = w1
        }
        return s
    }
}

internal class DetectableExecutionProbabilityModel(statistics: Statistics, maxCrashes: Int) : ProbabilityModel(statistics, maxCrashes) {
    private val c: Double

    init {
        val n = statistics.totalLength
        val e = maxCrashes.toDouble()
        c = if (n > 0) e / n else 0.0
    }

    override fun actorProbability(iThread: Int, iActor: Int) = c / (1 + c * statistics.actorLength(iThread, iActor))
    override fun isCrashAllowed(crashesNumber: Int) = crashesNumber < 2 * maxCrashes
}

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

    internal fun shouldSystemCrash() = bernoulli(randomSystemCrashProbability)
    internal fun shouldFlush() = bernoulli(RANDOM_FLUSH_PROBABILITY)
    internal fun shouldCrash(): Boolean {
        if (!state.crashesEnabled) return false
        val threadId = state.currentThreadId()
        val crashPointId = statistics.onCrashPoint(threadId)
        return moreCrashesPermitted() && bernoulli(model.crashPointProbability(threadId, crashPointId))
    }

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

    internal fun resetRandom(seed: Int) {
        modelCheckingRandom.setSeed(seed.toLong())
        randomGetter = { modelCheckingRandom }
    }

    internal fun generateSeed(): Int = iterationRandom.nextInt()

    internal fun onEnterActorBody(threadId: Int, actorId: Int) = statistics.onEnterActorBody(threadId, actorId)
    internal fun onExitActorBody(threadId: Int, actorId: Int) = statistics.onExitActorBody(threadId, actorId)
}
