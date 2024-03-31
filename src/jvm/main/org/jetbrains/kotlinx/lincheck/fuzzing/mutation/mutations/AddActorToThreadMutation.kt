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
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutation
import java.util.LinkedList

/**
 * Inserts random actor to thread with id `input.mutationThread` in parallel execution.
 */
class AddActorToThreadMutation(
    private val testStructure: CTestStructure,
    private val testConfiguration: CTestConfiguration
) : Mutation() {
    private val random = testStructure.randomProvider.createRandom()

    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val newParallelExecution = mutableListOf<MutableList<Actor>>()
        scenario.parallelExecution.map {
            newParallelExecution.add(it.toMutableList())
        }

        if (
            mutationThreadId >= 0 &&
            mutationThreadId < newParallelExecution.size &&
            newParallelExecution[mutationThreadId].size < testConfiguration.actorsPerThread
        ) {
            val generators = testStructure.actorGenerators.filter { !it.useOnce }
            val actor = generators.random().generate(mutationThreadId + 1, random)
            val insertBeforeIndex = random.nextInt(newParallelExecution[mutationThreadId].size + 1)

            println("Mutation: Add, threadId=$mutationThreadId, actor=${actor.method.name}, insertBefore=$insertBeforeIndex")
            val newActorsList = mutableListOf<Actor>()
            for(i in 0..newParallelExecution[mutationThreadId].size) {
                if (i < insertBeforeIndex) newActorsList.add(newParallelExecution[mutationThreadId][i])
                else if (i == insertBeforeIndex) newActorsList.add(actor)
                else newActorsList.add(newParallelExecution[mutationThreadId][i - 1])
            }

            newParallelExecution[mutationThreadId] = newActorsList
        }

        return ExecutionScenario(
            scenario.initExecution,
            newParallelExecution,
            scenario.postExecution,
            scenario.validationFunction
        )
    }

    override fun isApplicable(scenario: ExecutionScenario, mutationThreadId: Int): Boolean {
        return (mutationThreadId >= 0 &&
                mutationThreadId < scenario.parallelExecution.size &&
                scenario.parallelExecution[mutationThreadId].size < testConfiguration.actorsPerThread)
    }
}