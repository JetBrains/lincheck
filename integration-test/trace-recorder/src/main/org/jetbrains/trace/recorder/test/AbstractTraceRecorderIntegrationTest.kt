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

abstract class AbstractTraceRecorderIntegrationTest : AbstractTraceIntegrationTest() {
    override val fatJarName: String = "trace-recorder-fat.jar"

    final override fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: List<String>,
        gradleCommands: List<String>,
        checkRepresentation: Boolean,
    ) {
        runGradleTestImpl(
            testClassName,
            testMethodName,
            extraJvmArgs + listOf(
                "-Dlincheck.traceRecorderMode=true",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:hashCode=2"
            ),
            extraAgentArgs
                .plus("text")
                .plus("verbose"),
            gradleCommands,
            checkRepresentation
        )
    }
}
