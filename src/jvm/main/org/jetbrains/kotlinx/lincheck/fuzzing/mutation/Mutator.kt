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
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.mutations.*
import java.util.Random

class Mutator(
    private val random: Random,
    testStructure: CTestStructure,
    testConfiguration: CTestConfiguration
) {
    private val mutations = listOf(
        AddActorToParallelMutation(random, testStructure, testConfiguration),
        ReplaceActorInParallelMutation(random, testStructure),
        // RemoveActorFromParallelMutation(random),

        AddActorToInitMutation(random, testStructure, testConfiguration),
        AddActorToPostMutation(random, testStructure, testConfiguration),
        ReplaceActorInInitMutation(random, testStructure),
        ReplaceActorInPostMutation(random, testStructure, testConfiguration),
        // RemoveActorFromInitMutation(random),
        // RemoveActorFromPostMutation(random),
    )

    fun getAvailableMutations(scenario: ExecutionScenario, mutationThread: Int): List<Mutation> {
        return getAvailableMutations(mutations, scenario, mutationThread)
    }

    fun getRandomMutation(scenario: ExecutionScenario, mutationThread: Int): Mutation {
        val p = random.nextDouble()
        val parallelMutations = getAvailableMutations(
            listOf(mutations[0], mutations[1]),
            scenario,
            mutationThread
        )
        val otherMutations = getAvailableMutations(
            mutations.filter { !parallelMutations.contains(it) },
            scenario,
            mutationThread
        )

        // println("Parallel mutations: ${parallelMutations.size}, non-parallel mutations: ${otherMutations.size}")

        return if (
            (p <= PARALLEL_PART_MUTATION_THRESHOLD && parallelMutations.isNotEmpty()) ||
            otherMutations.isEmpty()
        ) {
            // pick mutation to parallel part
            parallelMutations[random.nextInt(parallelMutations.size)]
        } else {
            // pick mutation to init or post part
            otherMutations[random.nextInt(otherMutations.size)]
        }
    }

    private fun getAvailableMutations(mutations: List<Mutation>, scenario: ExecutionScenario, mutationThread: Int): List<Mutation> {
        return mutations.filter { it.isApplicable(scenario, mutationThread) }
    }
}

private const val PARALLEL_PART_MUTATION_THRESHOLD: Double = 0.8