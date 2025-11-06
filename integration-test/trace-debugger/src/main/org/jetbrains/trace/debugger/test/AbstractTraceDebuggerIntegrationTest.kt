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

import AbstractGradleTraceIntegrationTest
import java.io.File

abstract class AbstractTraceDebuggerIntegrationTest : AbstractGradleTraceIntegrationTest() {
    override val fatJarName: String = "trace-debugger-fat.jar"

    override fun runTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        commands: List<String>,
        outputFile: File
    ) {
        super.runTestImpl(
            testClassName,
            testMethodName,
            extraJvmArgs + listOf(
                "-Dlincheck.traceDebuggerMode=true",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:hashCode=2"
            ),
            extraAgentArgs,
            commands,
            outputFile
        )
    }
}
