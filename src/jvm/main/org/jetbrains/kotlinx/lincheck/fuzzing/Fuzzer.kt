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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.Coverage
import org.jetbrains.kotlinx.lincheck.fuzzing.input.FailedInput
import org.jetbrains.kotlinx.lincheck.fuzzing.input.Input
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.util.*
import kotlin.collections.ArrayDeque

/**
 * Entry point for fuzzing. This class encapsulates all logic related to scenarios fuzzing.
 *
 * @param seedScenarios contains scenarios that should be used as initial seeds (eg. custom scenarios).
 * @param defaultExecutionGenerator execution generator that will produce random scenarios to start up the fuzzing process.
 * It is still used even if `seedScenarios` is not empty.
 */
class Fuzzer(
    seedScenarios:  List<ExecutionScenario>,
    private val defaultExecutionGenerator: ExecutionGenerator
) {
    /** Includes coverage from failed executions as well */
    private val totalCoverage = Coverage()
    private var totalExecutions = 0

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

    init {
        // schedule seed scenarios for execution
        seedScenarios.forEach {
            seedInputs.add(Input(it))
        }
    }

    fun nextScenario(): ExecutionScenario {
        if (seedInputs.isNotEmpty()) {
            // try using seed input (custom scenario, if present)
            currentInput = seedInputs.removeFirst()
        }
        else if (savedInputs.isEmpty()) {
            // if no saved inputs exist, try picking completely random scenario
            currentInput = Input(defaultExecutionGenerator.nextExecution())
        }
        else {
            // pick something from fuzzing queue
            val parentInput = getCurrentParentInput()
            currentInput = parentInput.mutate()
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

        currentInput!!.executionDurationMs = (executionStart!!.time - Date().time).toInt()
        currentInput!!.coverage = coverage

        val newCoverageFound: Boolean = totalCoverage.merge(coverage)

        if (failure != null) {
            // TODO: check if failure is unique, then save, otherwise skip
            // save to failed inputs
            failures.add(FailedInput(currentInput!!, failure))
        }
        else if (newCoverageFound) {
            // update total coverage and save input if it uncovers new program regions
            savedInputs.add(currentInput!!)
        }

        // TODO: move printing in some logger
        println(
            "[Fuzzer, ${executionStart!!.time}] #$totalExecutions:" +
            "covered-branches=${coverage.branchesCoveredCount()} " +
            "total-branches=${totalCoverage.branchesCoveredCount()}"
        )

        totalExecutions++
        executionStart = null
    }

    fun getFirstFailure(): LincheckFailure? {
        return if (failures.isNotEmpty()) failures[0].error
        else null
    }

    private fun getCurrentParentInput(): Input {
        val prevParentInput = savedInputs[currentParentInputIdx]
        val targetChildrenInputs = getTargetChildrenCount(prevParentInput)

        if (childrenGeneratedForCurrentParentInput >= targetChildrenInputs) {
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
}

// constants match the JQF implementation
private const val CHILDREN_INPUTS_GENERATED = 5 // 50
private const val FAVORITE_PARENT_CHILDREN_MULTIPLIER = 1 // 20