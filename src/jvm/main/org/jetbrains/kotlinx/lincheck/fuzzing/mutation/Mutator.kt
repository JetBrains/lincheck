/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.fuzzing.mutation

import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.execution.ActorGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.Fuzzer
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.mutations.*
import java.util.Random

class Mutator(
    fuzzer: Fuzzer,
    testStructure: CTestStructure,
    testConfiguration: CTestConfiguration
) {
    private val random = fuzzer.random
//    private val generatorsUsage: MutableMap<ActorGenerator, Int> = LinkedHashMap<ActorGenerator, Int>().apply {
//        testStructure.actorGenerators.forEach {
//            put(it, 0)
//        }
//    }
    private val mutations = listOf(
        // AddActorToParallelMutation(random, testStructure, testConfiguration),
        // RemoveActorFromParallelMutation(random),
        ReplaceActorInParallelMutation(random, testStructure),

        CrossProductMutation(random, fuzzer.savedInputs),
        RandomInputMutation(random, fuzzer.defaultExecutionGenerator),

        // AddActorToInitMutation(random, testStructure, testConfiguration),
        // AddActorToPostMutation(random, testStructure, testConfiguration),
        ReplaceActorInInitMutation(random, testStructure),
        ReplaceActorInPostMutation(random, testStructure, testConfiguration),
        // RemoveActorFromInitMutation(random),
        // RemoveActorFromPostMutation(random),
    )

//    fun getAvailableMutations(scenario: ExecutionScenario, mutationThread: Int): List<Mutation> {
//        return getAvailableMutations(mutations, scenario, mutationThread)
//    }

    fun getRandomMutation(scenario: ExecutionScenario, mutationThread: Int, mutationNumber: Int): Mutation {
        val p = random.nextDouble()
        val parallelMutations = getAvailableMutations(
            listOf(mutations[0]),
            scenario,
            mutationThread
        )
        val globalMutations = getAvailableMutations(
            mutableListOf(mutations[1]).apply {
                // add random scenario mutation if it is the first mutation in sequence
                if (mutationNumber == 0) add(mutations[2])
          },
            scenario,
            mutationThread
        )
        val otherMutations = getAvailableMutations(
            mutations.subList(3, mutations.size),
            scenario,
            mutationThread
        )

        return if (p <= PARALLEL_PART_MUTATION_THRESHOLD && parallelMutations.isNotEmpty()) {
            // pick mutation to parallel part
            parallelMutations[random.nextInt(parallelMutations.size)]
        }
        else if (p <= GLOBAL_PART_MUTATION_THRESHOLD && globalMutations.isNotEmpty() || otherMutations.isEmpty()) {
            // pick global mutation
            globalMutations[random.nextInt(globalMutations.size)]
        }
        else {
            // pick mutation to init or post part
            otherMutations[random.nextInt(otherMutations.size)]
        }
    }

    private fun getAvailableMutations(mutations: List<Mutation>, scenario: ExecutionScenario, mutationThread: Int): List<Mutation> {
        return mutations.filter { it.isApplicable(scenario, mutationThread) }
    }
}

private const val PARALLEL_PART_MUTATION_THRESHOLD: Double = 0.65
private const val GLOBAL_PART_MUTATION_THRESHOLD: Double = 0.85
