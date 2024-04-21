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
import org.jetbrains.kotlinx.lincheck.fuzzing.input.Input
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutation
import java.util.*

class CrossProductMutation(
    random: Random,
    private val savedInputs: List<Input>
) : Mutation(random) {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val crossInputId  = random.nextInt(savedInputs.size)
        val crossInput    = savedInputs[crossInputId]
        var crossThreadId = random.nextInt(crossInput.scenario.parallelExecution.size)
        while (crossThreadId == mutationThreadId && crossInput.scenario == scenario)
            crossThreadId = random.nextInt(crossInput.scenario.parallelExecution.size)

        println("Mutation: Cross, " +
                "threadId=$mutationThreadId, " +
                "crossThreadId=$crossThreadId, " +
                "crossInput=\n${crossInput.scenario}")

        return ExecutionScenario(
            scenario.initExecution,
            scenario.parallelExecution.mapIndexed { index, actors ->
               if (index == mutationThreadId) {
                   println("Thread to change found: $index -> $crossThreadId")
                   crossInput.scenario.parallelExecution[crossThreadId]
               }
               else actors
            },
            scenario.postExecution,
            scenario.validationFunction
        )
    }

    override fun isApplicable(scenario: ExecutionScenario, mutationThreadId: Int): Boolean {
        return savedInputs.isNotEmpty()
    }

}