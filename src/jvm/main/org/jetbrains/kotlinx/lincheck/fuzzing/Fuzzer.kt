/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.fuzzing

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.Coverage
import org.jetbrains.kotlinx.lincheck.fuzzing.input.FailedInput
import org.jetbrains.kotlinx.lincheck.fuzzing.input.Input
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutator
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.collections.ArrayList
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

/**
 * Entry point for fuzzing. This class encapsulates all logic related to scenarios fuzzing.
 *
 * @param seedScenarios contains scenarios that should be used as initial seeds (eg. custom scenarios).
 * @param defaultExecutionGenerator execution generator that will produce random scenarios to start up the fuzzing process.
 * It is still used even if `seedScenarios` is not empty.
 */
class Fuzzer(
    seedScenarios:  List<ExecutionScenario>,
    testStructure: CTestStructure,
    testConfiguration: CTestConfiguration,
    private val defaultExecutionGenerator: ExecutionGenerator
) {
    /** Includes coverage from failed executions as well */
    private val totalCoverage = Coverage()
    private var totalExecutions = 0
    /** Includes invalid executions as well */
    private var maxCoveredBranches = 0

    /** Id of input that is going to be mutated to get some interesting children inputs */
    private var currentParentInputIdx = 0
    /** Number of children for current parent input that were already generated */
    private var childrenGeneratedForCurrentParentInput = 0
    /** Input that is going to be run by `Fuzzer` class user */
    private var currentInput: Input? = null
    private var executionStart: Date? = null


    /** Contains initial seeds (eg. custom scenarios, that user may specify), might be empty as well */
    private val seedInputs: ArrayDeque<Input> = ArrayDeque()
    /** Contains main queue of inputs which is used during fuzzing (this does not contain failed inputs, ony valid once) */
    private val savedInputs: MutableList<Input> = mutableListOf()
    /** Contains failures that were found during fuzzing. */
    private val failures: MutableList<FailedInput> = mutableListOf()
    var iterationOfFirstFailure = -1

    /** Utilities for fuzzing process */
    private val random = testStructure.randomProvider.createRandom()
    private val mutator = Mutator(random, testStructure, testConfiguration)

    init {
        // schedule seed scenarios for execution
        seedScenarios.forEach {
            seedInputs.add(Input(it))
        }
    }

    private fun getTrimmedParallelPart(randomScenario: ExecutionScenario): List<List<Actor>> {
        val parallelTrimmed = ArrayList<MutableList<Actor>>()
        repeat(randomScenario.parallelExecution.size) {
            parallelTrimmed.add(mutableListOf())
        }

        parallelTrimmed.forEachIndexed { index, actors ->
            actors.add(randomScenario.parallelExecution[index][0])
        }
        return parallelTrimmed
    }

    fun nextScenario(): ExecutionScenario {
        if (seedInputs.isNotEmpty()) {
            // try using seed input (custom scenario, if present)
            currentInput = seedInputs.removeFirst()
        }
        else if (savedInputs.isEmpty()) {
            // TODO: bring back random scenario generation
            // if no saved inputs exist, try picking completely random scenario
            val randomScenario = defaultExecutionGenerator.nextExecution()
            val trimmedScenario = ExecutionScenario(
                emptyList(),
                getTrimmedParallelPart(randomScenario),
                emptyList(),
                randomScenario.validationFunction
            )
            currentInput = Input(trimmedScenario) // defaultExecutionGenerator.nextExecution()
        }
        else {
            // pick something from fuzzing queue
            val parentInput = getCurrentParentInput()
            currentInput = parentInput.mutate(mutator, sampleGeometric(random, MEAN_MUTATION_COUNT), random)
            childrenGeneratedForCurrentParentInput++
        }

        executionStart = Date()

        return currentInput!!.scenario
    }

    fun handleResult(failure: LincheckFailure?, coverage: Coverage) {
        if (currentInput == null || executionStart == null)
            throw RuntimeException(
                "`Fuzzer::handleResult(...)` called with no input selected. " +
                "`Fuzzer::nextScenario()` must be called beforehand."
            )

        currentInput!!.executionDurationMs = (Date().time - executionStart!!.time).toInt()
        if (currentInput!!.coverage.coveredBranchesCount() != 0) {
            throw RuntimeException("Reassigning coverage that was already calculated")
        }
        currentInput!!.coverage = coverage
        maxCoveredBranches = max(maxCoveredBranches, coverage.coveredBranchesCount())

        // update total coverage
        val newCoverageFound: Boolean = totalCoverage.merge(coverage)

        if (failure != null) {
            // TODO: check if failure is unique, then save, otherwise skip
            // save to failed inputs
            failures.add(FailedInput(currentInput!!, failure))

            if (iterationOfFirstFailure == -1) iterationOfFirstFailure = totalExecutions
        }
        if (newCoverageFound) {
            // save input if it uncovers new program regions (failed inputs also saved)
            currentInput!!.favorite = true // set to favorite to generate more children from this input
            savedInputs.add(currentInput!!)
        }

        // TODO: move printing in some logger
        println(
            "[Fuzzer, ${executionStart!!}] #$totalExecutions: \n" +
            "covered-edges = ${coverage.coveredBranchesCount()} \n" +
            "max-edges = $maxCoveredBranches \n" +
            "total-edges = ${totalCoverage.coveredBranchesCount()} \n" +
            "sizes [saved (fav)/fails/seed] = ${savedInputs.size} (${savedInputs.count { it.favorite }}) / ${failures.size} / ${seedInputs.size} \n"
        )

        totalExecutions++
        executionStart = null

        // update `favorite` marks on saved inputs
        if (totalExecutions % FAVORITE_INPUTS_RECALCULATION_RATE == 0) {
            recalculateFavoriteInputs()
        }
    }

    fun getFirstFailure(): LincheckFailure? {
        return if (failures.isNotEmpty()) failures[0].error
        else null
    }

    private fun getCurrentParentInput(): Input {
        val prevParentInput = savedInputs[currentParentInputIdx]
        val targetChildrenCount = getTargetChildrenCount(prevParentInput)

        if (childrenGeneratedForCurrentParentInput >= targetChildrenCount) {
            // change parent input to the next one
            currentParentInputIdx = (currentParentInputIdx + 1) % savedInputs.size // TODO: completed cycle over the whole queue
            childrenGeneratedForCurrentParentInput = 0
        }

        return savedInputs[currentParentInputIdx]
    }

    private fun getTargetChildrenCount(parentInput: Input): Int {
        // TODO: add power scheduling
        // Baseline is a constant
        var target: Int = CHILDREN_INPUTS_GENERATED

        // fuzz favorite inputs more
        if (parentInput.favorite) {
            target *= FAVORITE_PARENT_CHILDREN_MULTIPLIER
        }

        return target
    }

    /**
     * Recalculates `favorite` marks for each input from `savedInputs` queue.
     *
     * Finds the subset of saved inputs that cover the same number of edges as in `totalCoverage` and marks them as favorite.
     * Non-favorite inputs are not discarded, but just marked as not favorite
     * (which decreases the number of children input generated from them)
     */
    private fun recalculateFavoriteInputs() {
        // reset favorites to false
        val favBefore: Int = savedInputs.count { it.favorite }
        val favAfter: Int

        savedInputs.forEach { it.favorite = false }

        // attempt to find the subset of inputs that produce the same coverage as `totalCoverage`
        val tempCoverage = Coverage()
        val coveredKeys = totalCoverage.coveredBranchesKeys()

        for (key in coveredKeys) {
            if (tempCoverage.isCovered(key)) continue

            val bestInput: Input = savedInputs.filter { it.coverage.isCovered(key) }.minByOrNull { it.fitness }
                ?: throw RuntimeException("Key $key is not covered in any saved input but was found in total coverage")

            tempCoverage.merge(bestInput.coverage)
            bestInput.favorite = true
        }

        favAfter = savedInputs.count { it.favorite }
        println("Recalculate favorite inputs: before=$favBefore, after=$favAfter")
    }

    /**
     * Sample from a geometric distribution with given mean.
     *
     * Utility method used in implementing mutation operations.
     *
     * @param random a pseudo-random number generator
     * @param mean the mean of the distribution
     * @return a randomly sampled value
     */
    private fun sampleGeometric(random: Random, mean: Double): Int {
        val p = 1 / mean
        val uniform = random.nextDouble()
        return ceil(ln(1 - uniform) / ln(1 - p)).toInt()
    }
}

// constants match the JQF implementation
private const val CHILDREN_INPUTS_GENERATED = 5 // 50
private const val FAVORITE_PARENT_CHILDREN_MULTIPLIER = 3 // 20
private const val FAVORITE_INPUTS_RECALCULATION_RATE = 30
private const val MEAN_MUTATION_COUNT = 4.0 // ceil(testConfiguration.actorsPerThread.toDouble() / 2.0).toInt()