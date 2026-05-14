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
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.zip.ZipFile

// Top-level package prefixes (in JVM internal form, with trailing `/`) allowed
// inside trace-agent fat jars. Anything else must either be shaded (see
// `packagesToShade` below) or excluded. Enforced by `verifyFatJarPackages`;
// the check exists to catch unrelocated transitive deps before they ship and
// collide with the user app's classpath — see PR #86, where `org/slf4j/*`
// leaked in unshaded via `org.java_websocket`.
private val FAT_JAR_ALLOWED_PACKAGE_PREFIXES: List<String> = listOf(
    "org/jetbrains/lincheck/",       // our own modules + shaded deps (`org.jetbrains.lincheck.shadow.*`)
    "sun/nio/ch/lincheck/",          // bootstrap classes
    "kotlin/",                        // kotlin-stdlib + kotlin-reflect runtime
    "org/jetbrains/annotations/",     // JetBrains annotations (transitive from kotlin-reflect)
    "org/intellij/lang/annotations/", // JetBrains annotations (transitive from kotlin-reflect)
)

abstract class VerifyFatJarPackagesTask : DefaultTask() {
    @get:InputFile
    abstract val jar: RegularFileProperty

    @get:Input
    abstract val allowedPrefixes: ListProperty<String>

    @TaskAction
    fun verify() {
        val jarFile = jar.get().asFile
        val allowed = allowedPrefixes.get()
        val offenders = mutableListOf<String>()
        ZipFile(jarFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".class")) continue
                // Strip multi-release prefix so `META-INF/versions/9/foo/Bar.class` is
                // checked against the same whitelist as `foo/Bar.class`.
                val logicalName = if (entry.name.startsWith("META-INF/versions/")) {
                    entry.name.removePrefix("META-INF/versions/").substringAfter('/')
                } else {
                    entry.name
                }
                // `module-info.class` (or its versioned copy) has no package.
                if (logicalName == "module-info.class") continue
                if (allowed.none { logicalName.startsWith(it) }) {
                    offenders += entry.name
                }
            }
        }
        if (offenders.isNotEmpty()) {
            val sample = offenders.take(20).joinToString("\n  ", prefix = "  ")
            val more = if (offenders.size > 20) "\n  ...and ${offenders.size - 20} more" else ""
            throw GradleException(
                "Fat jar ${jarFile.name} contains ${offenders.size} class file(s) outside the whitelist:\n" +
                "$sample$more\n" +
                "Allowed package prefixes:\n" +
                allowed.joinToString("\n  ", prefix = "  ") + "\n" +
                "Either add a shade rule in TraceAgentTasks.kt or extend the whitelist."
            )
        }
    }
}

// Below are tasks that are used by the tracing agent plugin.
// When these jars are loaded the `-Dlincheck.traceRecorderMode=true` VM argument is expected
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
         * Shadowing will also substitute ALL strings containing package names in ALL source files
         * (known shadow-plugin behavior, see https://github.com/GradleUp/shadow/issues/232);
         * when adding a new shadowed package, use `listOf(...)` hack to circumvent package shadowing:
         * for instance, instead of `org.objectweb.asm`, use `listOf("org", "objectweb", "asm").joinToString(".")`.
         *
         * To minimize the affected surface area, all package-related string checks should be extracted into
         * separate utility functions and be kept in `common/src/main/org/jetbrains/lincheck/util/Utils.kt`.
         */
        val packagesToShade = listOf(
            "org.objectweb.asm",
            "net.bytebuddy",
            "org.java_websocket",
            "org.slf4j",
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

    // After the fat jar is built, walk it and fail if any class falls outside
    // `FAT_JAR_ALLOWED_PACKAGE_PREFIXES`. Catches unrelocated transitive deps
    // (e.g. `org/slf4j/*` leaking in unshaded) before they ship.
    val verifyFatJarPackages = tasks.register<VerifyFatJarPackagesTask>("${fatJarTaskName}VerifyPackages") {
        jar.set(traceAgentFatJar.flatMap { it.archiveFile })
        allowedPrefixes.set(FAT_JAR_ALLOWED_PACKAGE_PREFIXES)
    }
    traceAgentFatJar.configure { finalizedBy(verifyFatJarPackages) }
    tasks.named("check") { dependsOn(verifyFatJarPackages) }

    // Expose the fat jar as the primary artifact of this module's outgoing configurations.
    // This ensures that when composite builds substitute a dependency
    // on the fat jar's Maven coordinates with a project dependency,
    // the fat jar (not the regular jar) is provided to consumers.
    //
    // This is safe because these modules (trace-recorder, live-debugger) are leaf modules —
    // nothing within the lincheck build depends on them via `project(":trace-recorder")` etc.
    // The integration tests use direct file copies via `copyTraceAgentFatJar()`, not project dependencies.

    configurations.named("runtimeElements").configure {
        outgoing {
            artifacts.clear()
            artifact(traceAgentFatJar)
        }
    }
    configurations.named("apiElements").configure {
        outgoing {
            artifacts.clear()
            artifact(traceAgentFatJar)
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
