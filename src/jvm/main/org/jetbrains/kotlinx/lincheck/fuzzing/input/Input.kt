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

    fun mutate(): Input {
        // TODO: add mutation API
        return Input(this.scenario)
    }
}