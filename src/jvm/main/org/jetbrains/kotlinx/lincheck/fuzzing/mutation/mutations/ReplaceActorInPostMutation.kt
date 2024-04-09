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
import java.util.*


/**
 * Replaces random actor in post execution part of scenario.
 */
class ReplaceActorInPostMutation(
    random: Random,
    private val testStructure: CTestStructure,
    private val testConfiguration: CTestConfiguration
) : Mutation(random) {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val newPostExecution = mutableListOf<Actor>()

        val generators = testStructure.operationGroups
            .filter { it.nonParallel }
            .flatMap { it.actors.filter { actor -> !actor.useOnce } }
            .distinct() + testStructure.actorGenerators
        var generatorIndex = random.nextInt(generators.size)

        var actor = generators[generatorIndex].generate(testConfiguration.threads + 1, random)
        val replaceAtIndex = random.nextInt(scenario.postExecution.size)

        while (actor.method.name == scenario.postExecution[replaceAtIndex].method.name) {
            generatorIndex = random.nextInt(generators.size)
            actor = generators[generatorIndex].generate(testConfiguration.threads + 1, random)
        }

        println("Mutation: ReplacePost, " +
                "actor=${actor.method.name}(${actor.arguments.joinToString(", ") { it.toString() }}), " +
                "replaceAt=$replaceAtIndex")

        for(i in 0 until scenario.postExecution.size) {
            newPostExecution.add(
                if (i < replaceAtIndex) scenario.postExecution[i]
                else if (i == replaceAtIndex) actor
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
        return scenario.postExecution.isNotEmpty()
    }
}