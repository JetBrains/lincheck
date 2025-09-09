/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.debugger.test

import AbstractTraceIntegrationTest

abstract class AbstractTraceDebuggerIntegrationTest : AbstractTraceIntegrationTest() {
    override val fatJarName: String = "trace-debugger-fat.jar"

    final override fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: List<String>,
        gradleCommands: List<String>,
        checkRepresentation: Boolean,
        testNameSuffix: String?,
    ) {
        runGradleTestImpl(
            testClassName,
            testMethodName,
            extraJvmArgs + listOf(
                "-Dlincheck.traceDebuggerMode=true",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:hashCode=2"
            ),
            extraAgentArgs,
            gradleCommands
        )
    }
}