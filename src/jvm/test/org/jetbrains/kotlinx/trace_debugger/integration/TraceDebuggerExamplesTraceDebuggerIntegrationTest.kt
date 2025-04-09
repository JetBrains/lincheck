/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.trace_debugger.integration

import org.junit.Ignore
import org.junit.Test

class TraceDebuggerExamplesTraceDebuggerIntegrationTest: AbstractTraceDebuggerIntegrationTest() {
    override val projectPath: String = "build/integrationTestProjects/TraceDebuggerExamples"

    @Ignore("Different hash code value when running on CI")
    @Test
    fun `tests org_examples_integration_bugs_LinkedHashSetTest putAnObjectWithoutDefinedHashCode`() {
        runGradleTest(
            "org.examples.integration.bugs.LinkedHashSetTest",
            "putAnObjectWithoutDefinedHashCode",
            listOf(":test"),
        )
    }

    @Ignore("`class.java.declaredMethods` call returns nondeterministic results")
    @Test
    fun `tests org_examples_integration_bugs_ReflectionTest sortMethods`() {
        runGradleTest(
            "org.examples.integration.bugs.ReflectionTest",
            "sortMethods",
            listOf(":test"),
        )
    }
}