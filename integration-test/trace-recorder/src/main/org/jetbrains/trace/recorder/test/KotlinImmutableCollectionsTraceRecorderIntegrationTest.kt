/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test

import org.junit.Test
import java.nio.file.Paths

class KotlinImmutableCollectionsTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlinx.collections.immutable").toString()

    @Test
    fun `tests_contract_list_ImmutableListTest empty`() {
        runGradleTest(
            testClassName = "tests.contract.list.ImmutableListTest",
            testMethodName = "empty",
            gradleCommands = listOf(
                ":kotlinx-collections-immutable:cleanJvmTest",
                ":kotlinx-collections-immutable:jvmTest",
            )
        )
    }

    @Test
    fun `tests_contract_list_ImmutableListTest`() {
        runGradleTests(
            testClassNamePrefix = "tests.contract.list.ImmutableListTest",
            gradleBuildCommands = listOf("compileTestKotlinJvm"),
            gradleTestCommands = listOf(
                ":kotlinx-collections-immutable:cleanJvmTest",
                ":kotlinx-collections-immutable:jvmTest",
            ),
            checkRepresentation = false
        )
    }
}