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
import kotlin.random.Random

/**
 * Class that holds statistics for some program run. It contains coverage, execution time,
 * and some other information that is used for prioritizing inputs in fuzzing queue (meaning `Fuzzed::savedInputs`).
 *
 * @param scenario the scenario that is associated with the current input.
 */
class Input(
    val scenario: ExecutionScenario
) {
    var coverage = Coverage()
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
    var fitness: Int = 0
        get() {
            if (executionDurationMs == -1) return Int.MAX_VALUE
            // TODO: check formula from AFL
            return (executionDurationMs / 100) * scenario.size // this is neither AFL, nor JQF implementation
        }

    /** Number of mutations (children) that were produced from this input. */
    private var mutationsPerformed: Long = 0

    /** Number of mutations to perform before mutation thread id change. */
    private var mutationThreadSwitchRate: Long = 20

    /** Id of thread that is going to be mutated when this input is selected as parent.
     *  This variable changes to some random thread every `mutationThreadSwitchRate` mutations.
     * */
    var mutationThread: Int = -1 // set to some appropriate thread id in `mutate()` method

    fun mutate(mutator: Mutator): Input {
        // TODO: add mutation API
        if (mutationsPerformed % mutationThreadSwitchRate == 0L) {
            updateMutationThread()
        }
        mutationsPerformed++

        // TODO: somehow pass the thread id to mutations (but don't create new object instances, maybe)
        val mutations = mutator.getAvailableMutations(this)
        val mutatedScenario = mutations.random().mutate(this)
        return Input(mutatedScenario)
    }

    private fun updateMutationThread() {
        mutationThread = Random.nextInt(scenario.threads.size)
    }
}