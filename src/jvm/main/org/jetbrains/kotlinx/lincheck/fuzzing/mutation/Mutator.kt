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
import org.jetbrains.kotlinx.lincheck.fuzzing.util.Sampling
import java.util.Random
import kotlin.math.min

class Mutator(
    fuzzer: Fuzzer,
    testStructure: CTestStructure,
    testConfiguration: CTestConfiguration
) {
    private val random = fuzzer.random
    private val policy = MutationPolicy(random, testStructure)
    private val mutations = listOf(
        ReplaceActorInParallelMutation(policy),

        CrossProductMutation(policy, fuzzer.savedInputs, fuzzer.failures),
        RandomInputMutation(policy, fuzzer.defaultExecutionGenerator),

        ReplaceActorInInitMutation(policy, testStructure),
        ReplaceActorInPostMutation(policy, testStructure, testConfiguration),
    )

    fun mutate(scenario: ExecutionScenario, meanMutationsCount: Double): ExecutionScenario {
        policy.refresh()

        // picking random scenario
        if (shouldPickRandom()) {
            println("Perform mutations: 1 (random)")
            return mutations[2].mutate(scenario)
        }

        // applying modifications to the passed scenario
        var mutationsCount = min(
            Sampling.sampleGeometric(random, meanMutationsCount),
            scenario.nThreads + scenario.size // max number of crosses and replaces
        )
        val crossesCount =
            if (shouldPickCross(scenario, mutationsCount)) {
                if (mutationsCount > scenario.size) scenario.nThreads
                else 1 + random.nextInt(scenario.nThreads)
            }
            else 0
        var mutatedScenario = scenario

        println("Perform mutations: $mutationsCount (cross=$crossesCount, replace=${mutationsCount - crossesCount})")

        repeat(mutationsCount) {
            mutatedScenario =
                if (it < crossesCount) applyCrossMutation(mutatedScenario) // apply 'cross' mutation
                else applyReplaceMutation(mutatedScenario) // apply 'replace' mutation
        }

        return mutatedScenario
    }

    fun updateGeneratorsWeights(reward: Double) = policy.updateGeneratorsWeights(reward)

    private fun applyCrossMutation(scenario: ExecutionScenario): ExecutionScenario = mutations[1].mutate(scenario)
    private fun applyReplaceMutation(scenario: ExecutionScenario): ExecutionScenario {
        if (shouldPickNonParallelReplace(scenario)) {
            val nonParallelReplaceMutations = mutableListOf<Mutation>().apply {
                if (policy.hasUniqueInitPositions(scenario)) add(mutations[3])
                if (policy.hasUniquePostPositions(scenario)) add(mutations[4])
            }

            return nonParallelReplaceMutations[random.nextInt(nonParallelReplaceMutations.size)]
                .mutate(scenario)
        }
        else {
            return mutations[0].mutate(scenario)
        }
    }

    private fun shouldPickRandom(): Boolean = random.nextDouble() < RANDOM_MUTATION_PROBABILITY
    private fun shouldPickCross(scenario: ExecutionScenario, totalMutations: Int): Boolean {
        return (
            scenario.size < totalMutations ||
            random.nextDouble() < CROSS_MUTATION_PROBABILITY
        )
    }
    private fun shouldPickNonParallelReplace(scenario: ExecutionScenario): Boolean {
        return(
            (
                (policy.hasUniqueInitPositions(scenario) || policy.hasUniquePostPositions(scenario)) &&
                random.nextDouble() < NON_PARALLEL_REPLACE_MUTATION_PROBABILITY
            ) ||
            !policy.hasUniqueParallelPositions(scenario)
        )
    }
}

//private const val PARALLEL_PART_MUTATION_THRESHOLD: Double = 0.65
//private const val GLOBAL_PART_MUTATION_THRESHOLD: Double = 0.85

private const val RANDOM_MUTATION_PROBABILITY = 0.1
private const val CROSS_MUTATION_PROBABILITY = 0.1
private const val NON_PARALLEL_REPLACE_MUTATION_PROBABILITY = 0.05
