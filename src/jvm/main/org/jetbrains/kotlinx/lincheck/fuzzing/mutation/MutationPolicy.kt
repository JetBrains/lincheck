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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.execution.ActorGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.fuzzing.Fuzzer
import java.util.*

class MutationPolicy(
    val random: Random,
    testStructure: CTestStructure
) {
    // TODO: add generators for init and post parts but with no weighted probabilities

    /** Storing used replace locations */
    private val usedParallelReplaceLocations: MutableList<Pair<Int, Int>> = mutableListOf()
    private val usedInitReplaceLocations: MutableList<Int> = mutableListOf()
    private val usedPostReplaceLocations: MutableList<Int> = mutableListOf()

    /** Storing weighted probabilities for actor generators */
    private val generatorRewards: MutableMap<ActorGenerator, Double> =
            testStructure.actorGenerators.associateWith { 1.0 }.toMutableMap()
    private val usedGenerators: MutableSet<ActorGenerator> = mutableSetOf()

    // utility methods for managing the state of policy
    fun refresh() {
        usedGenerators.clear()

        usedParallelReplaceLocations.clear()
        usedInitReplaceLocations.clear()
        usedPostReplaceLocations.clear()
    }

    fun updateGeneratorsWeights(reward: Double) {
        // update weighted probabilities for actor generators
        if (usedGenerators.isNotEmpty()) {
            val increment = reward / usedGenerators.size
            usedGenerators.forEach { generator ->
                generatorRewards[generator] = generatorRewards[generator]!! + increment
            }
        }
    }

    // generating random data for mutations using policy state
    /**
     * Finds non-used position for parallel actor replace.
     * @return pair of `threadId` and `replaceAt` index in `scenario.parallelExecution`
     */
    fun getUniqueParallelActorPosition(scenario: ExecutionScenario): Pair<Int, Int> {
        if (usedParallelReplaceLocations.size == scenario.parallelExecution.sumOf { it.size }) {
            throw RuntimeException("All parallel actors positions were used by policy")
        }

        var mutationThreadId: Int
        var replaceAt: Int

        do {
            mutationThreadId = random.nextInt(scenario.parallelExecution.size)
            replaceAt = random.nextInt(scenario.parallelExecution[mutationThreadId].size)
        }
        while (usedParallelReplaceLocations.contains(Pair(mutationThreadId, replaceAt)))

        val result = Pair(mutationThreadId, replaceAt)
        usedParallelReplaceLocations.add(result)
        return result
    }

    /**
     * Finds non-used position for init actor replace.
     * @return `replaceAt` index in `scenario.initExecution`
     */
    fun getUniqueInitActorPosition(scenario: ExecutionScenario): Int {
        if (usedInitReplaceLocations.size == scenario.initExecution.size) {
            throw RuntimeException("All init actors positions were used by policy")
        }

        val replaceAt = getUniqueNonParallelActorPosition(scenario.initExecution.size, usedInitReplaceLocations)
        usedInitReplaceLocations.add(replaceAt)
        return replaceAt
    }

    /**
     * Finds non-used position for post actor replace.
     * @return `replaceAt` index in `scenario.postExecution`
     */
    fun getUniquePostActorPosition(scenario: ExecutionScenario): Int {
        if (usedPostReplaceLocations.size == scenario.postExecution.size) {
            throw RuntimeException("All post actors positions were used by policy")
        }

        val replaceAt = getUniqueNonParallelActorPosition(scenario.postExecution.size, usedPostReplaceLocations)
        usedPostReplaceLocations.add(replaceAt)
        return replaceAt
    }

    /** Returns actor generator for parallel execution part based on weighted probabilities */
    fun getActorGenerator(filter: (ActorGenerator) -> Boolean): ActorGenerator {
        val generators = generatorRewards.entries.filter { filter(it.key) }
        val index = random.nextDouble(generators.sumOf { it.value })
        var accumulator = 0.0

        println("Select actor generator: sumToCollect=${index}, generators=[${
            generators.joinToString(", ", "{", "}") { it.key.method.name + ": " + it.value.toString() }
        }]")

        generators.forEach { (generator, reward) ->
            accumulator += reward
            if (accumulator >= index) {
                usedGenerators.add(generator)
                return generator
            }
        }

        throw RuntimeException("No matching generators exist. Check for filtering condition.")
    }

    fun hasUniqueParallelPositions(scenario: ExecutionScenario): Boolean = usedParallelReplaceLocations.size < scenario.parallelExecution.sumOf { it.size }
    fun hasUniqueInitPositions(scenario: ExecutionScenario): Boolean = usedInitReplaceLocations.size < scenario.initExecution.size
    fun hasUniquePostPositions(scenario: ExecutionScenario): Boolean = usedPostReplaceLocations.size < scenario.postExecution.size

    private fun getUniqueNonParallelActorPosition(totalPositions: Int, usedPositions: List<Int>): Int {
        var replaceAt: Int
        do {
            replaceAt = random.nextInt(totalPositions)
        }
        while (usedPositions.contains(replaceAt))
        return replaceAt
    }
}