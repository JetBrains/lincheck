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

import org.jetbrains.kotlinx.lincheck_test.AbstractIntegrationTest
import java.nio.file.Paths

abstract class AbstractTraceRecorderIntegrationTest : AbstractIntegrationTest() {
    override val testSourcesPath = Paths.get("src", "jvm", "test-trace-recorder-integration").toString()

    override fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: List<String>,
        gradleCommands: List<String>
    ) {
        runGradleTestImpl(
            testClassName,
            testMethodName,
            extraJvmArgs.plus("-Dlincheck.traceRecorderMode=true"),
            extraAgentArgs.plus("verbose"),
            gradleCommands
        )
    }
}
