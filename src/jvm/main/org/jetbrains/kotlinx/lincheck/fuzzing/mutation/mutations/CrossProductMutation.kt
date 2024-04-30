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
import org.jetbrains.kotlinx.lincheck.fuzzing.input.FailedInput
import org.jetbrains.kotlinx.lincheck.fuzzing.input.Input
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutation
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.MutationPolicy
import java.util.*

class CrossProductMutation(
    policy: MutationPolicy,
    private val savedInputs: List<Input>,
    private val failures: List<FailedInput>
) : Mutation(policy) {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val random = policy.random
        val crossInputId  = random.nextInt(savedInputs.size + failures.size)
        val crossInput    =
            if (crossInputId < savedInputs.size) savedInputs[crossInputId]
            else failures[crossInputId - savedInputs.size].input
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