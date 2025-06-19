/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("DEPRECATION", "UNUSED_VARIABLE")

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.api.plugins.JavaPluginExtension

// Below are tasks that are used by the trace debugger plugin.
// When these jars are loaded the `-Dlincheck.traceDebuggerMode=true` or `-Dlincheck.traceRecorderMode=true` VM argument is expected
fun Project.registerTraceAgentTasks() {
    // Ensure the Java plugin is applied (for sourceSets and runtimeClasspath)
    plugins.apply("java")

    val javaPluginExtension = extensions.getByType<JavaPluginExtension>()
    val mainSourceSet = javaPluginExtension.sourceSets.getByName("main")

    val runtimeClasspath = configurations.getByName("runtimeClasspath")

    val traceAgentFatJar = tasks.register<Jar>("traceAgentFatJar") {
        archiveBaseName.set("lincheck-fat")
        archiveVersion.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        dependsOn("bootstrapJar")

        // Include compiled sources
        from(mainSourceSet.output)

        // Include runtime dependencies (ASM, ByteBuddy, etc.)
        from({
            runtimeClasspath.resolve().filter { it.name.endsWith(".jar") }.map {
                if (it.isDirectory) it else zipTree(it)
            }
        })

        manifest {
            attributes(
                mapOf(
                    "Premain-Class" to "org.jetbrains.kotlinx.lincheck.traceagent.TraceAgent",
                    "Can-Redefine-Classes" to "true",
                    "Can-Retransform-Classes" to "true"
                )
            )
        }
    }

    // This jar is useful to add as a dependency to a test project to be able to debug
    val traceAgentJarNoDeps = tasks.register<Jar>("traceAgentJarNoDeps") {
        archiveBaseName.set("nodeps-trace-debugger")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(mainSourceSet.output)
        dependsOn(":bootstrap:jar")

        from(zipTree(file("${project(":bootstrap").buildDir}/libs/bootstrap.jar")))
    }
}
