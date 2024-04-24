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

import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.execution.ActorGenerator
import org.jetbrains.kotlinx.lincheck.fuzzing.Fuzzer

class MutationPolicy(
    fuzzer: Fuzzer,
    testStructure: CTestStructure
) {
    val random = fuzzer.random
    private val generatorRewards: MutableMap<ActorGenerator, Double> =
            testStructure.actorGenerators.associateWith { 1.0 }.toMutableMap()
    private val usedGenerators: MutableSet<ActorGenerator> = mutableSetOf()

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

    fun refresh() {
        usedGenerators.clear()
    }

    fun update(reward: Double) {
        if (usedGenerators.isNotEmpty()) {
            val increment = reward / usedGenerators.size
            usedGenerators.forEach { generator ->
                generatorRewards[generator] = generatorRewards[generator]!! + increment
            }
        }
    }
}