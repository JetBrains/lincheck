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
 * Inserts random actor to post execution part of scenario.
 */
class AddActorToPostMutation(
    policy: MutationPolicy,
    private val testStructure: CTestStructure,
    private val testConfiguration: CTestConfiguration
) : Mutation(policy) {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val random = policy.random
        val newPostExecution = mutableListOf<Actor>()

        val generators = testStructure.operationGroups
            .filter { it.nonParallel }
            .flatMap { it.actors.filter { actor -> !actor.useOnce } }
            .distinct() + testStructure.actorGenerators
        val generatorIndex = random.nextInt(generators.size)

        val actor = generators[generatorIndex].generate(testConfiguration.threads + 1, random)
        val insertBeforeIndex = random.nextInt(scenario.postExecution.size + 1)

        println("Mutation: AddPost, " +
                "actor=${actor.method.name}(${actor.arguments.joinToString(", ") { it.toString() }}), " +
                "insertBefore=$insertBeforeIndex")

        for(i in 0..scenario.postExecution.size) {
            newPostExecution.add(
                if (i < insertBeforeIndex) scenario.postExecution[i]
                else if (i == insertBeforeIndex) actor
                else scenario.postExecution[i - 1]
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
        return (
            scenario.postExecution.size < testConfiguration.actorsAfter &&
            scenario.parallelExecution.none { actors ->
                actors.any { it.isSuspendable }
            }
        )
    }
}