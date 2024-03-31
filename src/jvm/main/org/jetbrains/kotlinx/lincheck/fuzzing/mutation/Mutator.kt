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
import org.jetbrains.kotlinx.lincheck.fuzzing.input.Input
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.mutations.AddActorToThreadMutation
import org.jetbrains.kotlinx.lincheck.fuzzing.mutation.mutations.RemoveActorFromThreadMutation

class Mutator(
    testStructure: CTestStructure,
    testConfiguration: CTestConfiguration
) {
    private val mutations = listOf(
        RemoveActorFromThreadMutation(),
        AddActorToThreadMutation(testStructure, testConfiguration)
    )

    fun getAvailableMutations(scenario: ExecutionScenario, mutationThread: Int): List<Mutation> {
        return mutations.filter { it.isApplicable(scenario, mutationThread) }
    }
}