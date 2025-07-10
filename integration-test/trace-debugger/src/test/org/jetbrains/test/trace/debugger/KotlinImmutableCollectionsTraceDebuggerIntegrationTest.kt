/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.test.trace.debugger

import org.junit.Ignore
import org.junit.Test
import java.nio.file.Paths

class KotlinImmutableCollectionsTraceDebuggerIntegrationTest: AbstractTraceDebuggerIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlinx.collections.immutable").toString()

    @Ignore
    @Test
    fun `tests_contract_list_GuavaImmutableListTest list`() {
        runGradleTest(
            testClassName = "tests.contract.list.GuavaImmutableListTest",
            testMethodName = "list",
            gradleCommands = listOf(
                ":kotlinx-collections-immutable:cleanJvmTest",
                ":kotlinx-collections-immutable:jvmTest",
            )
        )
    }
}
