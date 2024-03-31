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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.input.Input
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutation
import kotlin.random.Random


/**
 * Removes random actor from thread with id `input.mutationThread` in parallel execution.
 * The thread is removed from parallel execution if no actors left in it.
 */
class RemoveActorFromThreadMutation : Mutation() {
    override fun mutate(input: Input): ExecutionScenario {
        val scenario = input.scenario
        val mutationThreadId = input.mutationThread

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
                if (actors.size == 1) return@mapIndexed null

                return@mapIndexed actors.toMutableList().apply {
                    removeAt(Random.nextInt(actors.size))
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

    override fun isApplicable(input: Input): Boolean {
        val scenario = input.scenario
        val mutationThreadId = input.mutationThread

        return (mutationThreadId >= 0 &&
                mutationThreadId < scenario.parallelExecution.size)
    }
}