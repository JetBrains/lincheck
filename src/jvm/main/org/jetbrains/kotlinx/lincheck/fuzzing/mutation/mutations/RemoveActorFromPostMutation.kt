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
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.MutationPolicy
import java.util.*

/**
 * Removes random actor from post execution part of scenario.
 */
class RemoveActorFromPostMutation(policy: MutationPolicy) : Mutation(policy) {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val removedIndex = policy.random.nextInt(scenario.postExecution.size)
        println("Mutation: RemovePost, index=$removedIndex")

        return ExecutionScenario(
            scenario.initExecution,
            scenario.parallelExecution,
            scenario.postExecution.toMutableList().apply { removeAt(removedIndex) },
            scenario.validationFunction
        )
    }

    override fun isApplicable(scenario: ExecutionScenario, mutationThreadId: Int): Boolean {
        return scenario.postExecution.isNotEmpty()
    }
}