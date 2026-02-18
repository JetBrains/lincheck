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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import java.io.File

// Below are tasks that are used by the trace debugger plugin.
// When these jars are loaded the `-Dlincheck.traceDebuggerMode=true` or `-Dlincheck.traceRecorderMode=true` VM argument is expected
fun Project.registerTraceAgentTasks(fatJarName: String, fatJarTaskName: String, premainClass: String) {
    // Ensure the Java plugin is applied (for sourceSets and runtimeClasspath)
    plugins.apply("java")
    plugins.apply("com.gradleup.shadow")

    val javaPluginExtension = extensions.getByType<JavaPluginExtension>()
    val mainSourceSet = javaPluginExtension.sourceSets.getByName("main")
    val runtimeClasspath = configurations.getByName("runtimeClasspath")
    val mainBuildDir: String = layout.buildDirectory.get().asFile.path


    val processedBootstrapJarPath = listOf(mainBuildDir, "bootstrap-tmp").joinToString(separator = File.separator)
    val boostrapBuildDir: String = project(":bootstrap").layout.buildDirectory.get().asFile.path

    
    val copyBootstrapJar = tasks.register<Copy>("copyBootstrapJar") {
        dependsOn(":bootstrapJar")
        from(file(
            listOf(boostrapBuildDir, "libs", "bootstrap.jar").joinToString(separator = File.separator)
        ))
        into(file(processedBootstrapJarPath))
    }
    
    // Hack to prevent unpacking bootstrap.jar during shadowing task.
    // When relocation starts, it will unwrap the outer archive and extract the inner one without change
    val jarWrapper = tasks.register<Jar>("jarWrapper") {
        destinationDirectory.set(file(
            listOf(processedBootstrapJarPath).joinToString(separator = File.separator)
        ))
        archiveFileName.set("deps-wrapper.jar")

        val bootstrapJarPath = listOf(processedBootstrapJarPath, "bootstrap.jar").joinToString(separator = File.separator)
        dependsOn(copyBootstrapJar)
        from(file(bootstrapJarPath))
    }

    val traceAgentFatJar = tasks.register<ShadowJar>(fatJarTaskName) {
        archiveBaseName.set(fatJarName)
        archiveVersion.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        dependsOn(jarWrapper)

        // Include compiled sources
        from(mainSourceSet.output)

        // Include runtime dependencies (ASM, ByteBuddy, etc.)
        from({
            runtimeClasspath.resolve().filter { it.name.endsWith(".jar") }.map {
                if (it.isDirectory) it else zipTree(it)
            }
        })
        from(jarWrapper)

        /* IMPORTANT NOTE!
         *
         * Shadowing will also substitute ALL strings containing package names in ALL source files;
         * when adding a new shadowed package, use `listOf(...)` hack to circumvent package shadowing:
         * for instance, instead of `org.objectweb.asm`, use `listOf("org", "objectweb", "asm").joinToString(".")`.
         *
         * To minimize the affected surface area, all package-related string checks should be extracted into
         * separate utility functions and be kept in `common/src/main/org/jetbrains/lincheck/util/Utils.kt`.
         */
        val packagesToShade = listOf(
            "org.objectweb.asm",
            "net.bytebuddy",
        )
        
        packagesToShade.forEach { packageName ->
            relocate(packageName, "org.jetbrains.lincheck.shadow.$packageName")
        }

        manifest {
            appendMetaAttributes(project)
            attributes(
                mapOf(
                    "Premain-Class" to premainClass,
                    "Agent-Class" to premainClass,
                    "Can-Redefine-Classes" to "true",
                    "Can-Retransform-Classes" to "true"
                )
            )
        }
    }

    // This jar is useful to add as a dependency to a test project to be able to debug
    val traceAgentJarNoDeps = tasks.register<Jar>("${fatJarTaskName}NoDeps") {
        archiveBaseName.set("nodeps-$fatJarName")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(mainSourceSet.output)
        dependsOn(":bootstrap:jar")

        from(zipTree(file("${project(":bootstrap").buildDir}/libs/bootstrap.jar")))
    }
}
