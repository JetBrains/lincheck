/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.jetbrains.lincheck.util.JdkVersion
import org.jetbrains.lincheck.util.jdkVersion

/**
 * Abstract base class for live debugger integration tests.
 *
 * In live debugger mode the agent does not accept `class` and `method` arguments.
 * Instead, breakpoints are supplied via a JSON file passed through the `breakpointsFile` agent argument,
 * and the mode is set to `liveDebugger`.
 */
abstract class AbstractGradleLiveDebuggerIntegrationTest : AbstractGradleTraceIntegrationTest() {

    override val fatJarName: String = "live-debugger-fat.jar"

    override val defaultJvmArgs: List<String> = listOf(
        "-Dlincheck.liveDebuggerMode=true",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:hashCode=2",
    )

    override val formatArgs: Map<String, String> = mapOf(
        "format" to "text",
        "formatOption" to "verbose",
    )

    // Gold trace files are recorded on JDK 17; running representation checks on other toolchains
    // produces noisy diffs from JDK-version-specific bytecode and stdlib internals.
    override val representationChecksEnabled: Boolean = (jdkVersion == JdkVersion.JDK_17)

    /**
     * In live debugger mode, the agent does not accept `class` and `method` arguments.
     */
    override fun buildAgentArgs(
        testClassName: String,
        testMethodName: String,
        pathToOutput: String,
        extraAgentArgs: Map<String, String>,
    ): String {
        return "output=${pathToOutput.escapeDollar()}" +
                extraAgentArgs.entries
                    .joinToString(",") { "${it.key}=${it.value.escapeDollar()}" }
                    .let { if (it.isNotEmpty()) ",$it" else it }
    }
}
