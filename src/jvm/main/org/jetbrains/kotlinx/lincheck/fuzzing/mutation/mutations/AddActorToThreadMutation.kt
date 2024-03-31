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
import org.jetbrains.kotlinx.lincheck.execution.ActorGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.input.Input
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutation

/**
 * Inserts random actor to thread with id `input.mutationThread` in parallel execution.
 */
class AddActorToThreadMutation(
    private val testStructure: CTestStructure,
    private val testConfiguration: CTestConfiguration
) : Mutation() {
    private val random = testStructure.randomProvider.createRandom()

    override fun mutate(input: Input): ExecutionScenario {
        val scenario = input.scenario
        val mutationThreadId = input.mutationThread

        val newParallelExecution = mutableListOf<MutableList<Actor>>()
        scenario.parallelExecution.map {
            newParallelExecution.add(it.toMutableList())
        }

        if (
            mutationThreadId >= 0 &&
            mutationThreadId < newParallelExecution.size &&
            newParallelExecution[mutationThreadId].size < testConfiguration.actorsPerThread
        ) {
            val actor = ArrayList<ActorGenerator>().apply {
                addAll(testStructure.actorGenerators.filter { !it.useOnce })
            }.random().generate(mutationThreadId + 1, random)

            newParallelExecution[mutationThreadId].add(actor)
        }

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
                mutationThreadId < scenario.parallelExecution.size &&
                scenario.parallelExecution[mutationThreadId].size < testConfiguration.actorsPerThread)
    }
}