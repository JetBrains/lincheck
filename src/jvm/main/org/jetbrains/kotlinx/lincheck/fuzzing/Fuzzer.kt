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

import fuzzing.stats.BenchmarkStats
import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.Coverage
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.HappensBeforeSummary
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.toCoverage
import org.jetbrains.kotlinx.lincheck.fuzzing.input.FailedInput
import org.jetbrains.kotlinx.lincheck.fuzzing.input.Input
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutator
import org.jetbrains.kotlinx.lincheck.fuzzing.util.Sampling
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.math.max

/**
 * Entry point for fuzzing. This class encapsulates all logic related to scenarios fuzzing.
 *
 * @param seedScenarios contains scenarios that should be used as initial seeds (eg. custom scenarios).
 * @param defaultExecutionGenerator execution generator that will produce random scenarios to start up the fuzzing process.
 * It is still used even if `seedScenarios` is not empty.
 */
class Fuzzer(
    private val stats: BenchmarkStats,
    seedScenarios:  List<ExecutionScenario>,
    testStructure: CTestStructure,
    testConfiguration: CTestConfiguration,
    internal val defaultExecutionGenerator: ExecutionGenerator
) {
    private var totalCycles = 0
    private var totalExecutions = 0
    private val totalCoverage = Coverage()
    private val validTotalCoverage = Coverage()
    private val totalTraceCoverage = Coverage()
    private var maxCoveredBranches = 0
    private var maxCoveredTrace = 0
    private val traces = mutableSetOf<HappensBeforeSummary>()

    ///** Id of input that is going to be mutated to get some interesting children inputs */
    //private var currentParentInputIdx = 0
    private var currentParentInput: Input? = null
    /** Number of children for current parent input that were already generated */
    private var childrenGeneratedForCurrentParentInput = 0
    /** Input that is going to be run by `Fuzzer` class user */
    private var currentInput: Input? = null
    private var executionStart: Date? = null

    /** Contains initial seeds (eg. custom scenarios, that user may specify), might be empty as well */
    private val seedInputs: ArrayDeque<Input> = ArrayDeque()
    /** Contains main queue of inputs which is used during fuzzing (this does not contain failed inputs, only valid once) */
    internal var savedInputs: MutableList<Input> = mutableListOf()
    /** Contains failures that were found during fuzzing. */
    private val failures: MutableList<FailedInput> = mutableListOf()
    var iterationOfFirstFailure = -1

    /** Utilities for fuzzing process */
    internal val random = testStructure.randomProvider.createRandom()
    private val mutator = Mutator(this, testStructure, testConfiguration)

    init {
        // schedule seed scenarios for execution
        seedScenarios.forEach {
            seedInputs.add(Input(it))
        }
    }

//    private fun getTrimmedParallelPart(randomScenario: ExecutionScenario): List<List<Actor>> {
//        val parallelTrimmed = ArrayList<MutableList<Actor>>()
//        repeat(randomScenario.parallelExecution.size) {
//            parallelTrimmed.add(mutableListOf())
//        }
//
//        parallelTrimmed.forEachIndexed { index, actors ->
//            actors.add(randomScenario.parallelExecution[index][0])
//        }
//        return parallelTrimmed
//    }

    fun nextScenario(): ExecutionScenario {
        if (seedInputs.isNotEmpty()) {
            // try using seed input (custom scenario, if present)
            currentInput = seedInputs.removeFirst()
        }
        else if (savedInputs.isEmpty()) {
            // TODO: bring back random scenario generation
            // if no saved inputs exist, try picking completely random scenario
            val randomScenario = defaultExecutionGenerator.nextExecution()
//            val trimmedScenario = ExecutionScenario(
//                emptyList(),
//                getTrimmedParallelPart(randomScenario),
//                emptyList(),
//                randomScenario.validationFunction
//            )
            currentInput = Input(randomScenario) // defaultExecutionGenerator.nextExecution()
        }
        else {
            // pick something from fuzzing queue
            val parentInput = getCurrentParentInput()
            currentInput = parentInput.mutate(mutator, Sampling.sampleGeometric(random, MEAN_MUTATION_COUNT), random)
            childrenGeneratedForCurrentParentInput++
        }

        executionStart = Date()

        return currentInput!!.scenario
    }

    fun handleResult(failure: LincheckFailure?, coverage: Coverage, traceCoverage: List<HappensBeforeSummary>) {
        if (currentInput == null || executionStart == null)
            throw RuntimeException(
                "`Fuzzer::handleResult(...)` called with no input selected. " +
                "`Fuzzer::nextScenario()` must be called beforehand."
            )

        currentInput!!.executionDurationMs = (Date().time - executionStart!!.time).toInt()
        if (currentInput!!.coverage.coveredBranchesCount() != 0) {
            throw RuntimeException("Reassigning coverage that was already calculated")
        }

        val prevTracesCount = traces.size
        val averageTraceDiff = prevTracesCount.toDouble() / (totalExecutions + 1.0)
        val traceCountAddedDiff: Double

        var coverageUpdated = false
        var traceCoverageUpdated = false
        var maxCoverageUpdated = false
        var maxTraceCoverageUpdated = false
        var traceCountAverageIncreasedSufficiently = false

        traces.addAll(traceCoverage)
        currentInput!!.coverage = coverage
        currentInput!!.traceCoverage = traceCoverage.map { it.toCoverage() }.fold(Coverage()) { acc, trace ->
            acc.merge(trace)
            acc
        }
        currentInput!!.totalNewTraces = traces.size - prevTracesCount
        traceCountAddedDiff = max(0.0, currentInput!!.totalNewTraces.toDouble() - averageTraceDiff)


        if (failure != null) {
            // TODO: check if failure is unique, then save, otherwise skip
            // save to failed inputs
            failures.add(FailedInput(currentInput!!, failure))

            if (iterationOfFirstFailure == -1) iterationOfFirstFailure = totalExecutions
        }

        val validCoverageUpdated = (failure == null && validTotalCoverage.merge(coverage))
        coverageUpdated = totalCoverage.merge(coverage) || validCoverageUpdated
        traceCoverageUpdated = totalTraceCoverage.merge(currentInput!!.traceCoverage)
        maxCoverageUpdated = maxCoveredBranches < coverage.coveredBranchesCount()
        maxTraceCoverageUpdated = maxCoveredTrace < currentInput!!.traceCoverage.coveredBranchesCount()
        traceCountAverageIncreasedSufficiently = (traceCountAddedDiff > 0.0 && traceCountAddedDiff / averageTraceDiff + random.nextDouble() > 0.7)

        val newInputFound: Boolean =
            coverageUpdated ||
            traceCoverageUpdated ||
            maxCoverageUpdated ||
            maxTraceCoverageUpdated ||
            traceCountAverageIncreasedSufficiently

        if (newInputFound) {
            // save input if it uncovers new program regions, or it maximizes single run coverage
            // currentInput!!.favorite = true // set to favorite to generate more children from this input

            maxCoveredBranches = max(maxCoveredBranches, coverage.coveredBranchesCount())
            maxCoveredTrace = max(maxCoveredTrace, currentInput!!.traceCoverage.coveredBranchesCount())

            val rewardFactor: Double = 0.5
            mutator.updatePolicy(
                reward =
                    0.5 * (if (coverageUpdated) rewardFactor else 0.0) +
                    0.2 * (if (traceCountAverageIncreasedSufficiently) rewardFactor else 0.0) +
                    0.2 * (if (traceCoverageUpdated) rewardFactor else 0.0) +
                    0.05 * (if (maxCoverageUpdated) rewardFactor else 0.0) +
                    0.05 * (if (maxTraceCoverageUpdated) rewardFactor else 0.0)
            )

            if (failure == null) {
                savedInputs.add(currentInput!!)
            }
        }


        stats.totalFoundCoverage.add(totalCoverage.coveredBranchesCount())
        stats.iterationFoundCoverage.add(coverage.coveredBranchesCount())
        stats.maxIterationFoundCoverage.add(maxCoveredBranches)
        stats.savedInputsCounts.add(savedInputs.size)
        if (failure != null) stats.failedIterations.add(totalExecutions + 1)
        stats.totalHappensBeforePairs.add(totalTraceCoverage.coveredBranchesCount())
        stats.iterationHappensBeforePairs.add(currentInput!!.traceCoverage.coveredBranchesCount())
        stats.maxIterationHappensBeforePairs.add(maxCoveredTrace)
        stats.distinctTraces.add(traces.size)

        // TODO: move printing in some logger
        println(
            "[Fuzzer, ${executionStart!!}] #$totalExecutions: \n" +
            "============ \n" +
            "covered-edges = ${coverage.coveredBranchesCount()} \n" +
            "max-edges = $maxCoveredBranches \n" +
            "total-edges = ${totalCoverage.coveredBranchesCount()} \n" +
            "valid-edges = ${validTotalCoverage.coveredBranchesCount()} \n" +
            "============ \n" +
            "covered-trace = ${currentInput!!.traceCoverage.coveredBranchesCount()} \n" +
            "max-trace = $maxCoveredTrace \n" +
            "total-trace = ${totalTraceCoverage.coveredBranchesCount()} \n" +
            "traces (avg/new) = ${traces.size} ($averageTraceDiff / ${currentInput!!.totalNewTraces}) \n" +
            "============ \n" +
            "mask (c|t|mc|mt|ta): ${if (coverageUpdated) 1 else 0}${if (traceCoverageUpdated) 1 else 0}${if (maxCoverageUpdated) 1 else 0}${if (maxTraceCoverageUpdated) 1 else 0}${if (traceCountAverageIncreasedSufficiently) 1 else 0} \n" +
            "cycles: $totalCycles \n" +
            "sizes [saved (fav)/fails/seed] = ${savedInputs.size} (${savedInputs.count { it.favorite }}) / ${failures.size} / ${seedInputs.size} \n"
        )

        totalExecutions++
        executionStart = null

        // update `favorite` marks on saved inputs
        if (totalExecutions % FAVORITE_INPUTS_RECALCULATION_RATE == 0) {
            //currentParentInputIdx = 0
            currentParentInput = null
            childrenGeneratedForCurrentParentInput = 0
            savedInputs.forEach { it.usedAsParentDuringCycle = false }
            recalculateFavoriteInputs()
        }
    }

    fun getFirstFailure(): LincheckFailure? {
        return if (failures.isNotEmpty()) failures[0].error
        else null
    }

    private fun getCurrentParentInput(): Input {
        //val targetChildrenCount = getTargetChildrenCount(currentParentInput!!)
        val getBestParentInput: () -> Input? =
                { savedInputs.filter { !it.usedAsParentDuringCycle }.maxByOrNull { it.coverageFitness } }

        if (currentParentInput == null || childrenGeneratedForCurrentParentInput >= getTargetChildrenCount(currentParentInput!!)) {
            // change parent input to the next one
            //currentParentInputIdx = (currentParentInputIdx + 1) % savedInputs.size
            currentParentInput?.usedAsParentDuringCycle = true
            currentParentInput = getBestParentInput()
            childrenGeneratedForCurrentParentInput = 0

            if (currentParentInput == null) {
                totalCycles++
                savedInputs.forEach { it.usedAsParentDuringCycle = false }
                recalculateFavoriteInputs()
                currentParentInput = getBestParentInput()
            }
        }

        return currentParentInput!!
        //return savedInputs[currentParentInputIdx]
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
     */
    private fun recalculateFavoriteInputs() {
        // reset favorites to false
        val favoritesBefore: Int = savedInputs.count { it.favorite }
        val newInputsQueue = mutableListOf<Input>()

        savedInputs.forEach { it.favorite = false }

        // attempt to find the subset of inputs that produce the same total coverage
        val tempCoverage = Coverage()
        val coveredKeys = totalCoverage.coveredBranchesKeys()
        for (key in coveredKeys) {
            if (tempCoverage.isCovered(key)) continue

            val bestInput: Input? = savedInputs.filter { it.coverage.isCovered(key) }.maxByOrNull { it.coverageFitness }
                //?: throw RuntimeException("Key $key is not covered in any saved input but was found in total coverage")

            if (bestInput != null) {
                // bestInput is not a failed input
                tempCoverage.merge(bestInput.coverage)
                bestInput.favorite = true
                newInputsQueue.add(bestInput)
            }
        }

        // attempt to find the subset of inputs that produce the same total happens-before pairs coverage
        val tempTraceCoverage = Coverage()
        val coveredTraceKeys = totalTraceCoverage.coveredBranchesKeys()
        for (key in coveredTraceKeys) {
            if (tempTraceCoverage.isCovered(key)) continue

            val bestInput: Input? =
                savedInputs.filter { it.traceCoverage.isCovered(key) }.maxByOrNull { it.traceFitness }
            //?: throw RuntimeException("Key $key is not covered in any saved input but was found in total trace coverage")

            if (bestInput != null) {
                // bestInput is not a failed input
                tempTraceCoverage.merge(bestInput.traceCoverage)
                bestInput.favorite = true
                if (!newInputsQueue.contains(bestInput)) newInputsQueue.add(bestInput)
            }
        }

        savedInputs = newInputsQueue
        println("Recalculate favorite inputs: before=$favoritesBefore, after=${newInputsQueue.size}")
    }
}

private const val CHILDREN_INPUTS_GENERATED = 3
private const val FAVORITE_PARENT_CHILDREN_MULTIPLIER = 2
private const val FAVORITE_INPUTS_RECALCULATION_RATE = 15
private const val MEAN_MUTATION_COUNT = 4.0