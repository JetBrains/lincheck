/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.impl

import org.jetbrains.trace.recorder.test.runner.AbstractTraceRecorderIntegrationTest
import org.junit.Test
import java.nio.file.Paths

class KotlinImmutableCollectionsTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlinx.collections.immutable").toString()

    @Test
    fun `tests_contract_list_ImmutableListTest empty with include filter`() {
        runTest(
            testClassName = "tests.contract.list.ImmutableListTest",
            testMethodName = "empty",
            commands = listOf(
                ":kotlinx-collections-immutable:cleanJvmTest",
                ":kotlinx-collections-immutable:jvmTest",
            ),
            extraAgentArgs = mapOf(
                "include" to "tests.contract.list.ImmutableListTest",
                "lazyInstrumentation" to "false"
            ),
            testNameSuffix = "with_include_filter",
        )
    }

    @Test
    fun `tests_contract_list_ImmutableListTest empty with exclude filter`() {
        runTest(
            testClassName = "tests.contract.list.ImmutableListTest",
            testMethodName = "empty",
            commands = listOf(
                ":kotlinx-collections-immutable:cleanJvmTest",
                ":kotlinx-collections-immutable:jvmTest",
            ),
            extraAgentArgs = mapOf("exclude" to "kotlinx.collections.immutable.*"),
            testNameSuffix = "with_exclude_filter",
        )
    }
}
