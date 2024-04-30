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


/**
 * Replaces random actor in post execution part of scenario.
 */
class ReplaceActorInPostMutation(
    policy: MutationPolicy,
    private val testStructure: CTestStructure,
    private val testConfiguration: CTestConfiguration
) : Mutation(policy) {
    override fun mutate(scenario: ExecutionScenario): ExecutionScenario {
        val newPostExecution = mutableListOf<Actor>()

        // TODO: move this generation to policy
        val generators = testStructure.operationGroups
            .filter { it.nonParallel }
            .flatMap { it.actors.filter { actor -> !actor.useOnce } }
            .distinct() + testStructure.actorGenerators

        val replaceAtIndex = policy.getUniquePostActorPosition(scenario)
        var actor: Actor

        do {
            val generatorIndex = policy.random.nextInt(generators.size)
            actor = generators[generatorIndex].generate(testConfiguration.threads + 1, policy.random)
        }
        while (actor.method.name == scenario.postExecution[replaceAtIndex].method.name)

        println("Mutation: ReplacePost, " +
                "actor=${actor.method.name}(${actor.arguments.joinToString(", ") { it.toString() }}), " +
                "replaceAt=$replaceAtIndex")

        for(i in 0 until scenario.postExecution.size) {
            newPostExecution.add(
                if (i == replaceAtIndex) actor
                else scenario.postExecution[i]
            )
        }

        return ExecutionScenario(
            scenario.initExecution,
            scenario.parallelExecution,
            newPostExecution,
            scenario.validationFunction
        )
    }

    override fun isApplicable(scenario: ExecutionScenario, mutationThreadId: Int): Boolean {
        return scenario.postExecution.isNotEmpty()
    }
}