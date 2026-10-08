/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.File

abstract class AbstractGradleTraceIntegrationTest: AbstractTraceIntegrationTest() {

    protected open fun buildAgentArgs(
        testClassName: String,
        testMethodName: String,
        pathToOutput: String,
        extraAgentArgs: Map<String, String>,
    ): String {
        return "class=${testClassName.escapeDollar()},method=${testMethodName.escapeDollar()},output=${pathToOutput.escapeDollar()}" +
                extraAgentArgs.entries
                    .joinToString(",") { "${it.key}=${it.value.escapeDollar()}" }
                    .let { if (it.isNotEmpty()) ",$it" else it }
    }

    override fun runTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        commands: List<String>,
        outputFile: File
    ) = runGradleTest(
        testClassName, testMethodName, "$testClassName.$testMethodName",
        extraJvmArgs, extraAgentArgs, commands, outputFile
    )

    protected fun runGradleTest(
        agentClassName: String,
        agentMethodName: String,
        testFilter: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        commands: List<String>,
        outputFile: File
    ) {
        createGradleConnection().use { connection ->
            connection
                .newBuild()
                .setStandardError(System.err)
                .addArguments(
                    "--build-cache",
                    "--init-script",
                    createInitScriptAsTempFile(
                        buildGradleInitScriptToDumpTrace(
                            commands, agentClassName, agentMethodName, outputFile, extraJvmArgs, extraAgentArgs
                        )
                    ).absolutePath,
                ).forTasks(
                    *commands.toTypedArray(),
                    "--tests",
                    testFilter,
                ).run()
        }
    }

    /**
     * Creates a new gradle connection to the project from [projectPath].
     */
    protected fun createGradleConnection(): ProjectConnection = GradleConnector
        .newConnector()
        .forProjectDirectory(File(projectPath))
        .connect()

    protected fun createInitScriptAsTempFile(content: String): File {
        val tempFile = File.createTempFile("initScript", ".gradle.kts")
        tempFile.deleteOnExit()
        tempFile.writeText(content)
        return tempFile
    }

    protected fun buildGradleInitScriptToDumpTrace(
        gradleCommands: List<String>,
        testClassName: String,
        testMethodName: String,
        fileToDump: File,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
    ): String {
        // We need to escape it twice, as our argument parser will de-escape it when split into array
        val pathToOutput = fileToDump.absolutePath.escape().escape()
        val agentArgs = buildAgentArgs(testClassName, testMethodName, pathToOutput, extraAgentArgs)
        return """
            // Init scripts apply to every build in the invocation -- buildSrc and the included builds
            // resolving settings plugins (e.g. kotlin's `internal-gradle-setup`, ktor's `build-settings-logic`).
            // The requested task paths can only match in the invoked root build, so both the instrumentation
            // and the not-attached check below are scoped to it.
            if (gradle.parent == null) gradle.taskGraph.whenReady {
                val gradleCommands = listOf(${gradleCommands.joinToString(",") { "\"$it\"" }})
                val jvmTasks = allTasks.filter { task -> task is JavaForkOptions && gradleCommands.contains(task.path) }
                // Task paths are absolute, so a relative command ("test" instead of ":test") matches nothing
                // and the build silently runs without the agent -- surfacing only as an empty trace file,
                // which is indistinguishable from the agent having failed. Fail on the mismatch instead.
                if (jvmTasks.isEmpty()) {
                    error(
                        "Trace agent not attached: none of " + gradleCommands + " is a JVM task of this build. " +
                        "JVM tasks in the graph: " + allTasks.filter { it is JavaForkOptions }.map { it.path }
                    )
                }
                jvmTasks.forEach { task ->
                    // Compilation can reuse the cache; the trace must come from a fresh instrumented test JVM.
                    task.outputs.upToDateWhen { false }
                    task.outputs.cacheIf { false }
                    task.doFirst {
                        val options = task as JavaForkOptions
                        val jvmArgs = options.jvmArgs?.toMutableList() ?: mutableListOf()
                        jvmArgs.addAll(listOf(${extraJvmArgs.joinToString(", ") { "\"$it\"" }}))
                        jvmArgs.add("-javaagent:$pathToFatJar=$agentArgs")
                        // We need to append our agent args the latest, so it comes
                        // after any user-specified args (including other agents).
                        // The only reliable way to do this is via `jvmArgumentProviders`
                        options.jvmArgumentProviders.add(CommandLineArgumentProvider { jvmArgs })
                    }
                }
            }
        """.trimIndent()
    }
}

abstract class AbstractWholeApplicationTraceIntegrationTest: AbstractGradleTraceIntegrationTest() {

    override fun runTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        commands: List<String>,
        outputFile: File
    ) = runGradleTest(
        // class & method names are empty strings to run TR in whole-application mode
        agentClassName = "", agentMethodName = "", testFilter = "$testClassName.$testMethodName",
        extraJvmArgs, extraAgentArgs, commands, outputFile
    )
}
