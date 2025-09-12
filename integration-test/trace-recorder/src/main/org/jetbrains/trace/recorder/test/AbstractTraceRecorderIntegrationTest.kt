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

import AbstractTraceIntegrationTest
import withStdErrTee

abstract class AbstractTraceRecorderIntegrationTest : AbstractTraceIntegrationTest() {
    override val fatJarName: String = "trace-recorder-fat.jar"

    final override fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        gradleCommands: List<String>,
        checkRepresentation: Boolean,
        testNameSuffix: String?,
        onStdErrOutput: (String) -> Unit,
    ) {
        val (_, output) = withStdErrTee {
            runGradleTestImpl(
                testClassName,
                testMethodName,
                extraJvmArgs + listOf(
                    "-Dlincheck.traceRecorderMode=true",
                    "-XX:+UnlockExperimentalVMOptions",
                    "-XX:hashCode=2",
                ),
                extraAgentArgs+ mapOf(
                    "format" to "text",
                    "formatOption" to "verbose",),
                gradleCommands,
                checkRepresentation,
                testNameSuffix,
            )
        }
        onStdErrOutput(output)
    }
}
