/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.io.File

abstract class AbstractIntellijTraceIntegrationTest: AbstractTraceIntegrationTest() {
    override val fatJarName: String = "trace-recorder-fat.jar"

    override fun runTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        commands: List<String>,
        outputFile: File
    ) {
        if (!File("$projectPath/android").exists()) {
            ProcessBuilder("$projectPath/getPlugins.sh")
                .directory(File(projectPath))
                .inheritIO().start().waitFor()
        }

        // We need to escape it twice, as our argument parser will de-escape it when split into array
        val pathToOutput = outputFile.absolutePath.escape().escape()
        ProcessBuilder(
            "./tests.cmd",
            "-Dintellij.build.test.patterns=${testClassName.escapeDollar()}",
            "-Dintellij.build.test.trace.recorder.enabled=true",
            "-Dintellij.build.test.trace.recorder.className=${testClassName.escapeDollar()}",
            "-Dintellij.build.test.trace.recorder.methodName=${testMethodName.escapeDollar()}",
            "-Dintellij.build.test.trace.recorder.traceDump=$pathToOutput",
            "-Dintellij.build.test.trace.recorder.agentJar=$pathToFatJar"
        ).directory(File(projectPath))
            .inheritIO().start().waitFor()
    }
}
