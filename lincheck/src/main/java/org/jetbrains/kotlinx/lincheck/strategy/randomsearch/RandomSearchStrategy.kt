/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.randomsearch

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.ManagedStrategyBase
import org.jetbrains.kotlinx.lincheck.util.PseudoRandom
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * RandomSearchStrategy at first studies codeLocations at the choosen thread
 * until a blocking event or a thread finish and
 * then chooses one uniform randomly
 */
class RandomSearchStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        verifier: Verifier,
        testCfg: RandomSearchCTestConfiguration,
        reporter: Reporter
) : ManagedStrategyBase(testClass, scenario, verifier, reporter, testCfg.maxRepetitions, testCfg.checkObstructionFreedom) {
    // an increasing id of operation performed within this execution
    private val executionPosition = AtomicInteger(0)
    // ids of operations where a thread should be switched
    private val switchPositions = mutableListOf<Int>()
    // id of last operation till last thread we switched to finished
    private var nextFinishPosition: Int? = null
    // maximum number of thread switches that managed strategy may use to search for incorrect execution
    private val maxInvocations = testCfg.invocationsPerIteration
    // number of used invocationsPerIteration
    private var usedInvocations = 0

    // fields for approximation of width of interleaving tree
    private var variantsSum = 0L
    private var variantsCount = 0
    // maximum number of switches that stratefy tries to use
    private var maxLevel = 1

    @Throws(Exception::class)
    override fun run() {
        // should only used once for each Strategy object

        try {
            while (usedInvocations < maxInvocations) {
                searchForExecution(0)
                executionRandom.endLastPoint() // a point corresponds to one search branch
            }
        } finally {
            runner.close()
        }
    }

    override fun onFinish(iThread: Int) {
        // the reason to increament execution position here is to
        // add possibility not to add a switch between last thread switch and
        // the moment when we must to switch
        executionPosition.incrementAndGet()
        super.onFinish(iThread)
    }

    override fun onNewSwitch() {
        val position = executionPosition.get()

        // if next border position is undefined
        if (nextFinishPosition == null) {
            // try update it
            if (switchPositions.isEmpty() || switchPositions.last() < position) // strictly after last switch
                nextFinishPosition = position
        }
    }

    override fun shouldSwitch(iThread: Int): Boolean {
        // the increment of current position is made in the same place as where the check is
        // because the position check and the position increment are dual operations
        executionPosition.incrementAndGet()
        return executionPosition.get() in switchPositions
    }

    private fun searchForExecution(level: Int) {
        if (usedInvocations >= maxInvocations) return

        // run invocation
        val results = runInvocation()
        checkResults(results)

        val finishPosition = nextFinishPosition ?: return

        val startPosition = if (switchPositions.isEmpty()) 0 else switchPositions.last() + 1

        val variantsNumber = finishPosition - startPosition + 1

        // update statistics
        variantsSum += variantsNumber
        variantsCount++

        val avgVariants = variantsSum / variantsCount

        val interleavingTreeEstimation = maxLevel * avgVariants.pow(maxLevel)

        if (nThreads * interleavingTreeEstimation <= usedInvocations) maxLevel++

        // halt if necessary
        if (level == maxLevel) return

        // choose next switch
        val switchPosition = (startPosition..finishPosition).random(executionRandom)

        // run recursively
        switchPositions.add(switchPosition)
        searchForExecution(level + 1)
        switchPositions.removeAt(switchPositions.lastIndex)

        return
    }

    override fun initializeInvocation() {
        nextFinishPosition = null
        executionPosition.set(-1) // one step before zero
        usedInvocations++

        super.initializeInvocation()
    }
}

private fun Long.pow(n: Int): Long {
    var result = 1L

    repeat(n) {
        result *= this
    }

    return result
}

private fun IntRange.random(random: PseudoRandom): Int = random.nextInt(this.last - this.start + 1) + this.start
