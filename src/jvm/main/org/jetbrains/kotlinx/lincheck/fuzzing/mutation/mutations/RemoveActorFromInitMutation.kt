/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.fuzzing.mutation.mutations

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutation
import java.util.*

/**
 * Removes random actor from init execution part of scenario.
 */
class RemoveActorFromInitMutation(random: Random) : Mutation(random) {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val removedIndex = random.nextInt(scenario.initExecution.size)
        println("Mutation: RemoveInit, index=$removedIndex")

        return ExecutionScenario(
            scenario.initExecution.toMutableList().apply { removeAt(removedIndex) },
            scenario.parallelExecution,
            scenario.postExecution,
            scenario.validationFunction
        )
    }

    override fun isApplicable(scenario: ExecutionScenario, mutationThreadId: Int): Boolean {
        return scenario.initExecution.isNotEmpty()
    }
}