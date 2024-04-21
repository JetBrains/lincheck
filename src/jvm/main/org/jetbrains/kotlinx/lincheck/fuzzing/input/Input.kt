/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.fuzzing.input

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.Coverage
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutator
import org.jetbrains.kotlinx.lincheck.fuzzing.util.Sampling
import java.util.*
import kotlin.math.floor

/**
 * Class that holds statistics for some program run. It contains coverage, execution time,
 * and some other information that is used for prioritizing inputs in fuzzing queue (meaning `Fuzzed::savedInputs`).
 *
 * @param scenario the scenario that is associated with the current input.
 */
class Input(
    val scenario: ExecutionScenario
) {
    /** Coverage produced by input */
    var coverage = Coverage()
    /** Number of happens-before pairs produced by all traces of input */
    var traceCoverage = Coverage()
    /** New concurrent traces that input generated */
    var totalNewTraces = 0
    /** Time took for input to be executed by the specified number of Lincheck invocations. */
    var executionDurationMs: Int = -1

    /** Input might be marked as favorite every time when `Fuzzer::savedInputs` queue is rebalanced. */
    var favorite: Boolean = false

    /**
     * Score value that is used for marking inputs as favorite.
     *
     * When `Fuzzer::savedInput` queue is getting too big fuzzer will select the minimal number of inputs
     * that would still achieve the same coverage. The way it does that is as follows:
     *
     * - take the index in `Fuzzer::totalCoverage::hits` array that is still not covered by the favorite inputs.
     * - find the input that covers this index, if multiple exist take the one has the minimal `fitness` value.
     * - mark as covered all keys from this input and set its `favorite` field to `true`.
     * - repeat until all keys will be covered and discard non-favorite selected inputs.
     * */
    val coverageFitness: Long
        get() {
            if (executionDurationMs == -1) return 0
            return scenario.size.toLong() * coverage.coveredBranchesCount().toLong()
        }

    val traceFitness: Long
        get() {
            if (executionDurationMs == -1) return 0
            return traceCoverage.coveredBranchesCount().toLong() * totalNewTraces
        }

    /** Number of mutations (children) that were produced from this input. */
    private var mutationsPerformed: Long = 0

    /** Id of thread that is going to be mutated when this input is selected as parent.
     *  This variable changes to some random thread every `mutationThreadSwitchRate` mutations.
     * */
    private var mutationThread: Int = -1 // set to some appropriate thread id in `mutate()` method

    fun mutate(mutator: Mutator, mutationsCount: Int, random: Random): Input {
        println("Perform mutations: $mutationsCount")

        var mutatedScenario = scenario
        println("Before (fav=${this.favorite}): \n" + mutatedScenario.toString())

        repeat(mutationsCount) {
//            if (mutationsPerformed % mutationThreadSwitchRate == 0L) {
//                updateMutationThread()
//            }
            updateMutationThread(random)
            mutationsPerformed++

            // val mutations = mutator.getAvailableMutations(mutatedScenario, mutationThread)
            // if (mutations.isNotEmpty()) {
            //     val mutationIndex = random.nextInt(mutations.size)
            //     mutatedScenario = mutations[mutationIndex].mutate(mutatedScenario, mutationThread)
            // }
            mutatedScenario = mutator
                    .getRandomMutation(mutatedScenario, mutationThread, it)
                    .mutate(mutatedScenario, mutationThread)
        }

        println("After: \n" + mutatedScenario.toString())

        return Input(mutatedScenario)
    }

    private fun updateMutationThread(random: Random) {
        // TODO: implement weighted thread id selection
        //  (the smaller number of mutations performed for some thread the bigger chances of picking it)
        mutationThread = random.nextInt(scenario.threads.size)
    }
}