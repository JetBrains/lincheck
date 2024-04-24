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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import java.util.Random

abstract class Mutation(
    protected val policy: MutationPolicy
) {
    abstract fun mutate(scenario: ExecutionScenario, mutationThreadId: Int): ExecutionScenario
    open fun isApplicable(scenario: ExecutionScenario, mutationThreadId: Int): Boolean = true
}