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

import kotlinx.atomicfu.atomic
import kotlin.random.Random

object Probability {
    private const val RANDOM_FLUSH_PROBABILITY = 0.2f
    private val random_ = ThreadLocal.withInitial { Random(42) }
    private val random get() = random_.get()

    private var defaultCrashes = 0
    private var minimizeCrashes = false
    private var totalActors = 0L
    private val totalPossibleCrashes = atomic(0L)

    @Volatile
    private var randomSystemCrashProbability = 0.0f

    @Volatile
    private var singleCrashProbability = 0.0f

    @Volatile
    private var expectedCrashes = 0

    fun shouldFlush() = bernoulli(RANDOM_FLUSH_PROBABILITY)
    fun shouldCrash(): Boolean {
        if (RecoverableStateContainer.crashesEnabled && moreCrashesPermitted()) {
            totalPossibleCrashes.incrementAndGet()
            return bernoulli(singleCrashProbability)
        }
        return false
    }

    fun shouldSystemCrash() = bernoulli(randomSystemCrashProbability)

    fun resetExpectedCrashes() {
        minimizeCrashes = false
        expectedCrashes = defaultCrashes
        totalActors = 0
        totalPossibleCrashes.value = 0
    }

    fun minimizeCrashes() {
        minimizeCrashes = true
        expectedCrashes--
        totalActors = 0
        totalPossibleCrashes.value = 0
    }

    fun setNewInvocation(actors: Int, model: RecoverabilityModel) {
        randomSystemCrashProbability = model.systemCrashProbability()
        defaultCrashes = model.defaultExpectedCrashes()
        if (!minimizeCrashes && expectedCrashes != defaultCrashes) {
           resetExpectedCrashes()
        }
        updateSingleCrashProbability(actors)
        totalActors += actors.toLong()
    }

    fun reset() {
        defaultCrashes = 0
        minimizeCrashes = false
        totalActors = 0L
        totalPossibleCrashes.value = 0
        singleCrashProbability = 0.0f
    }

    private fun updateSingleCrashProbability(actors: Int) {
        val crashes = totalPossibleCrashes.value
        if (crashes == 0L) return
        singleCrashProbability = expectedCrashes / (actors * (crashes.toFloat() / totalActors))
    }

    private fun moreCrashesPermitted() = occurredCrashes() < expectedCrashes

    private fun occurredCrashes() = if (randomSystemCrashProbability < 1.0) {
        RecoverableStateContainer.crashesCount()
    } else {
        RecoverableStateContainer.maxCrashesCountPerThread()
    }

    private fun bernoulli(probability: Float) = random.nextFloat() < probability
}
