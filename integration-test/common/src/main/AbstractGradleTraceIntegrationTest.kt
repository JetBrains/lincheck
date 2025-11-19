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
import java.nio.file.Paths

abstract class AbstractGradleTraceIntegrationTest: AbstractTraceIntegrationTest() {
    override fun runTestImpl(
        testClassName: String,
        testMethodName: String,
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
                    "-Dorg.gradle.daemon=false",
                    "--init-script",
                    createInitScriptAsTempFile(
                        buildGradleInitScriptToDumpTrace(
                            commands, testClassName, testMethodName, outputFile, extraJvmArgs, extraAgentArgs
                        )
                    ).absolutePath,
                ).forTasks(
                    *commands.toTypedArray(),
                    "--tests",
                    "$testClassName.$testMethodName",
                ).run()
        }
    }

    /**
     * Creates a new gradle connection to the project from [projectPath].
     */
    private fun createGradleConnection(): ProjectConnection = GradleConnector
        .newConnector()
        .forProjectDirectory(File(projectPath))
        .connect()

    private fun createInitScriptAsTempFile(content: String): File {
        val tempFile = File.createTempFile("initScript", ".gradle.kts")
        tempFile.deleteOnExit()
        tempFile.writeText(content)
        return tempFile
    }

    private fun buildGradleInitScriptToDumpTrace(
        gradleCommands: List<String>,
        testClassName: String,
        testMethodName: String,
        fileToDump: File,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
    ): String {
        fun String.escapeDollar() = replace("$", "\\$")

        val pathToFatJar = File(Paths.get("build", "libs", fatJarName).toString()).absolutePath.escape()
        // We need to escape it twice, as our argument parser will de-escape it when split into array
        val pathToOutput = fileToDump.absolutePath.escape().escape()
        val agentArgs =
            "class=${testClassName.escapeDollar()},method=${testMethodName.escapeDollar()},output=${pathToOutput.escapeDollar()}" +
                    extraAgentArgs.entries
                        .joinToString(",") { "${it.key}=${it.value.escapeDollar()}" }
                        .let { if (it.isNotEmpty()) ",$it" else it }
        return """
            gradle.taskGraph.whenReady {
                val gradleCommands = listOf(${gradleCommands.joinToString(",") { "\"$it\"" }})
                val jvmTasks = allTasks.filter { task -> task is JavaForkOptions && gradleCommands.contains(task.path) }
                jvmTasks.forEach { task ->
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

    private fun String.escape(): String = this.replace("\\", "\\\\")
}
