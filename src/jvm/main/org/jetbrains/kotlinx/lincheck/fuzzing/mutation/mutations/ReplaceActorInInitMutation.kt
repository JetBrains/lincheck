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
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.Mutation
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.MutationPolicy
import java.util.*
import java.util.stream.Collectors
import kotlin.collections.ArrayList


/**
 * Replaces random actor in init execution part of scenario.
 */
class ReplaceActorInInitMutation(
    policy: MutationPolicy,
    private val testStructure: CTestStructure
) : Mutation(policy) {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val random = policy.random
        val newInitExecution = mutableListOf<Actor>()

        val generators = testStructure.actorGenerators.filter { !it.useOnce && !it.isSuspendable }
        var generatorIndex = random.nextInt(generators.size)

        var actor = generators[generatorIndex].generate(0, random)
        val replaceAtIndex = random.nextInt(scenario.initExecution.size)

        while (actor.method.name == scenario.initExecution[replaceAtIndex].method.name) {
            generatorIndex = random.nextInt(generators.size)
            actor = generators[generatorIndex].generate(0, random)
        }

        println("Mutation: ReplaceInit, " +
                "actor=${actor.method.name}(${actor.arguments.joinToString(", ") { it.toString() }}), " +
                "replaceAt=$replaceAtIndex")

        for(i in 0 until scenario.initExecution.size) {
            newInitExecution.add(
                if (i < replaceAtIndex) scenario.initExecution[i]
                else if (i == replaceAtIndex) actor
                else scenario.initExecution[i - 1]
            )
        }

        return ExecutionScenario(
            newInitExecution,
            scenario.parallelExecution,
            scenario.postExecution,
            scenario.validationFunction
        )
    }

    override fun isApplicable(scenario: ExecutionScenario, mutationThreadId: Int): Boolean {
        return scenario.initExecution.isNotEmpty()
    }
}