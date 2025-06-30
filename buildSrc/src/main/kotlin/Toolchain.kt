/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.provideDelegate
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths

fun KotlinJvmProjectExtension.configureKotlin() {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

fun JavaPluginExtension.configureJava() {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

fun JavaCompile.setupJavaToolchain(project: Project) {
    val jdkToolchainVersion: String by project
    val javaToolchains: JavaToolchainService = project.extensions.getByType(JavaToolchainService::class.java)
    setupJavaToolchain(javaToolchains, jdkToolchainVersion)
}

private fun JavaCompile.setupJavaToolchain(javaToolchains: JavaToolchainService,  jdkToolchainVersion: String) {
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(jdkToolchainVersion))
    })
}

fun KotlinCompile.setupKotlinToolchain(project: Project) {
    val jdkToolchainVersion: String by project
    val javaToolchains: JavaToolchainService = project.extensions.getByType(JavaToolchainService::class.java)
    setupKotlinToolchain(javaToolchains, jdkToolchainVersion)
}

private fun KotlinCompile.setupKotlinToolchain(javaToolchains: JavaToolchainService, jdkToolchainVersion: String) {
    kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(jdkToolchainVersion))
    })
}

fun KotlinCompile.getAccessToInternalDefinitionsOf(vararg projects: Project) {
    projects.forEach { project ->
        val mainSourceSet = project.extensions
            .getByType(JavaPluginExtension::class.java).sourceSets
            .getByName("main").output.classesDirs
        val jarArchive = Paths.get(project.layout.buildDirectory.get().asFile.absolutePath, "libs",
            if (project.name == "lincheck") project.name + "-" + project.version + ".jar"
            else project.name + ".jar"
        ).toFile()
        friendPaths.from(mainSourceSet + jarArchive)
    }
}

fun Test.configureJvmTestCommon(project: Project) {
    maxParallelForks = 1
    maxHeapSize = "6g"

    val instrumentAllClasses: String by project
    if (instrumentAllClasses.toBoolean()) {
        systemProperty("lincheck.instrumentAllClasses", "true")
    }
    // The `overwriteRepresentationTestsOutput` flag is used to automatically repair representation tests.
    // Representation tests work by comparing an actual output of the test (execution trace in most cases)
    // with the expected output stored in a file (which is kept in resources).
    // Normally, if the actual and expected outputs differ, the test fails,
    // but when this flag is set, instead the expected output is overwritten with the actual output.
    // This helps to quickly repair the tests when the output difference is non-essential
    // or when the output logic actually has changed in the code.
    // The test system relies on that the gradle test task is run from the root directory of the project,
    // to search for the directory where the expected output files are stored.
    //
    // PLEASE USE CAREFULLY: always first verify that the changes in the output are expected!
    val overwriteRepresentationTestsOutput: String by project
    if (overwriteRepresentationTestsOutput.toBoolean()) {
        systemProperty("lincheck.overwriteRepresentationTestsOutput", "true")
    }
    val extraArgs = mutableListOf<String>()
    val withEventIdSequentialCheck: String by project
    if (withEventIdSequentialCheck.toBoolean()) {
        extraArgs.add("-Dlincheck.debug.withEventIdSequentialCheck=true")
    }
    val testInTraceDebuggerMode: String by project
    if (testInTraceDebuggerMode.toBoolean()) {
        extraArgs.add("-Dlincheck.traceDebuggerMode=true")
        exclude("**/lincheck_test/guide/*")
    }
    val dumpTransformedSources: String by project
    if (dumpTransformedSources.toBoolean()) {
        extraArgs.add("-Dlincheck.dumpTransformedSources=true")
    }
    extraArgs.add("-Dlincheck.version=${project.version}")

    project.findProperty("lincheck.logFile")?.let { extraArgs.add("-Dlincheck.logFile=${it as String}") }
    project.findProperty("lincheck.logLevel")?.let { extraArgs.add("-Dlincheck.logLevel=${it as String}") }

    jvmArgs(extraArgs)
}