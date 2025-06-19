/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.trace_recorder.integration

import org.junit.Test

class TraceRecorderDumbTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = "build/integrationTestProjects/kotlinx.collections.immutable"

    @Test
    fun dumbTest() {
        println("Dumb trace recorder test!")
    }

    @Test
    fun test() {
        runGradleTest(
            testClassName = "tests.contract.list.ImmutableListTest",
            testMethodName = "empty",
            gradleCommands = listOf(
                ":kotlinx-collections-immutable:cleanJvmTest",
                ":kotlinx-collections-immutable:jvmTest",
            )
        )
    }
}
