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
        val testSourceSet = project.extensions
            .getByType(JavaPluginExtension::class.java).sourceSets
            .getByName("test").output.classesDirs

        val jarArchive = Paths.get(project.layout.buildDirectory.get().asFile.absolutePath, "libs",
            if (project.name == "lincheck") project.name + "-" + project.version + ".jar"
            else project.name + ".jar"
        ).toFile()
        friendPaths.from(mainSourceSet + testSourceSet + jarArchive)
    }
}