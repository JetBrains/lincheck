/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.transformation.crash.distribution

import kotlin.math.min

internal class StatisticsModel(val actorLengths: List<Int>, val recoveryLengths: List<Int>)

internal interface CrashProbabilityModel {
    val expectedCrashes: Double
    fun inActorCrashProbability(i: Int): Double
    fun inRecoveryCrashProbability(i: Int): Double
    fun isCrashAllowed(crashesNumber: Int): Boolean
    fun crashProbability(j: Int, conditionalTargetProbability: Double) =
        conditionalTargetProbability / (1 - j * conditionalTargetProbability)
}

internal class BasicCrashProbabilityModel(
    private val statistics: StatisticsModel,
    override val expectedCrashes: Double
) :
    CrashProbabilityModel {
    private val actors get() = statistics.actorLengths
    private val recoveries get() = statistics.recoveryLengths

    private val c = expectedCrashes / (actors.sum() + recoveries.sum())
    override fun inActorCrashProbability(i: Int) = c
    override fun inRecoveryCrashProbability(i: Int) = 1.0 / (actors[i] + recoveries[i])
    override fun isCrashAllowed(crashesNumber: Int) = true

    companion object {
        fun maxExpectedCrashes(statistics: StatisticsModel) =
            (statistics.actorLengths.sum() + statistics.recoveryLengths.sum()) / statistics.actorLengths.maxOrNull()!!
                .toDouble()
    }
}

internal class BoundedNoRecoverCrashProbabilityModel(
    statistics: StatisticsModel,
    override val expectedCrashes: Double,
    private val maxCrashes: Int
) : CrashProbabilityModel {
    private val c = expectedCrashes / statistics.actorLengths.sum()
    private val s = calculatePreviousCrashesProbabilities(statistics, c, maxCrashes)

    init {
        val n = statistics.actorLengths.size
        assert(c < (0 until n).map { i -> (if (i == 0) 1.0 else s[i - 1]) / statistics.actorLengths[i] }.minOrNull()!!)
    }

    override fun inActorCrashProbability(i: Int) = if (i == 0) c else c / s[i - 1]
    override fun inRecoveryCrashProbability(i: Int) = error("Should not crash in recovery")
    override fun isCrashAllowed(crashesNumber: Int) = crashesNumber < maxCrashes

    companion object {
        fun expectedCrashes(statistics: StatisticsModel, maxCrashes: Int): Double {
            val n = statistics.actorLengths.size
            val total = statistics.actorLengths.sum()
            val solver = BinarySearchSolver { c ->
                val p = calculatePreviousCrashesProbabilities(statistics, c, maxCrashes)
                c - (0 until n).map { i -> (if (i == 0) 1.0 else p[i - 1]) / statistics.actorLengths[i] }.minOrNull()!!
            }
            val mc = min(1.0 / statistics.actorLengths.maxOrNull()!!, maxCrashes.toDouble() / total)
            val c = solver.solve(0.0, mc, 1e-9)
            return c * total
        }

        private fun calculatePreviousCrashesProbabilities(
            statistics: StatisticsModel,
            c: Double,
            maxCrashes: Int
        ): DoubleArray {
            val actors = statistics.actorLengths
            val n = actors.size
            assert(statistics.recoveryLengths.all { it == 0 })
            val s = DoubleArray(n)
            val w = List(n) { DoubleArray(n + 1) }
            w[0][0] = 1 - c * actors[0]; w[0][1] = c * actors[0] // w[0][k] = 0.0
            s[0] = w[0][0] + if (maxCrashes == 1) 0.0 else w[0][1]
            for (i in 1 until n) {
                val z = c * actors[i] / s[i - 1]
                w[i][0] = (1 - z) * w[i - 1][0]
                for (k in 1..n) {
                    w[i][k] = z * w[i - 1][k - 1] + (1 - z) * w[i - 1][k]
                }
                for (k in 0 until maxCrashes) {
                    s[i] += w[i][k]
                }
            }
            return s
        }
    }
}

fun interface BinarySearchSolver {
    /**
     *  A monotonically increasing function.
     */
    fun f(x: Double): Double

    /**
     * Find x such that f(x) = 0. x in [[a], [b]].
     */
    fun solve(a: Double, b: Double, eps: Double): Double {
        var l = a
        var r = b
        while (r - l > eps) {
            val x = (r + l) / 2
            val fValue = f(x)
            when {
                fValue > 0 -> r = x
                fValue < 0 -> l = x
                else -> return x
            }
        }
        return l
    }
}
