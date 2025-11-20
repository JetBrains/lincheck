/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.debugger.test.impl

import org.jetbrains.trace.debugger.test.runner.AbstractTraceDebuggerIntegrationTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class TraceDebuggerExamplesTraceDebuggerIntegrationTest: AbstractTraceDebuggerIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "TraceDebuggerExamples").toString()

    @Test
    fun `org_examples_integration_bugs_LinkedHashSetTest putAnObjectWithoutDefinedHashCode`() {
        runTest(
            testClassName = "org.examples.integration.bugs.LinkedHashSetTest",
            testMethodName = "putAnObjectWithoutDefinedHashCode",
            commands = listOf(":test"),
        )
    }

    @Disabled("`class.java.declaredMethods` call returns nondeterministic results")
    @Test
    fun `org_examples_integration_bugs_ReflectionTest sortMethods`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ReflectionTest",
            testMethodName = "sortMethods",
            commands = listOf(":test"),
        )
    }
}