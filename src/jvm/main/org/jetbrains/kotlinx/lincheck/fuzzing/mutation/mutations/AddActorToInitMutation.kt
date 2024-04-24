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
 * Inserts random actor to init execution part of scenario.
 */
class AddActorToInitMutation(
    policy: MutationPolicy,
    private val testStructure: CTestStructure,
    private val testConfiguration: CTestConfiguration
) : Mutation(policy) {
    override fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario {
        val random = policy.random
        val newInitExecution = mutableListOf<Actor>()

        val generators = testStructure.actorGenerators.filter { !it.useOnce && !it.isSuspendable }
        val generatorIndex = random.nextInt(generators.size)

        val actor = generators[generatorIndex].generate(0, random)
        val insertBeforeIndex = random.nextInt(scenario.initExecution.size + 1)

        println("Mutation: AddInit, " +
                "actor=${actor.method.name}(${actor.arguments.joinToString(", ") { it.toString() }}), " +
                "insertBefore=$insertBeforeIndex")

        for(i in 0..scenario.initExecution.size) {
            newInitExecution.add(
                if (i < insertBeforeIndex) scenario.initExecution[i]
                else if (i == insertBeforeIndex) actor
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
        return scenario.initExecution.size < testConfiguration.actorsBefore
    }
}