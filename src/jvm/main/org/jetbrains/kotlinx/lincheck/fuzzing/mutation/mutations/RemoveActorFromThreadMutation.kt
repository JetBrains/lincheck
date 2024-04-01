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
 * Removes random actor from thread with id `input.mutationThread` in parallel execution.
 * The thread is removed from parallel execution if no actors left in it.
 */
class RemoveActorFromThreadMutation(
    private val random: Random
) : Mutation() {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
//        val newParallelExecution = mutableListOf<MutableList<Actor>>()
//        newParallelExecution.addAll(scenario.parallelExecution)
//
//        if (mutationThreadId >= 0 && mutationThreadId < newParallelExecution.size) {
//            newParallelExecution[mutationThreadId].toMutableList().apply {
//                removeAt(Random.nextInt(this.size))
//            }
//
//            if (newParallelExecution[mutationThreadId].isEmpty()) {
//                newParallelExecution.removeAt(mutationThreadId)
//            }
//        }

        val newParallelExecution = scenario.parallelExecution.mapIndexed { index, actors ->
            if (index == mutationThreadId) {
                val removedIndex = random.nextInt(actors.size)
                println("Mutation: Remove, threadId=$mutationThreadId, index=$removedIndex")

                if (actors.size == 1) return@mapIndexed null

                return@mapIndexed actors.toMutableList().apply {
                    removeAt(removedIndex)
                }
            }

            return@mapIndexed actors
        }.filterNotNull()

        return ExecutionScenario(
            scenario.initExecution,
            newParallelExecution,
            scenario.postExecution,
            scenario.validationFunction
        )
    }

    override fun isApplicable(scenario: ExecutionScenario, mutationThreadId: Int): Boolean {
        return (mutationThreadId >= 0 &&
                mutationThreadId < scenario.parallelExecution.size)
    }
}