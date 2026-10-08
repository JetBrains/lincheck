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

// The only classes allowed at the javaagent jar root.
// `-javaagent` appends that jar to the system class path,
// so whatever is visible there is visible to the application and can collide with its own classpath.
// Enforced by `VerifyFatJarTask`.
private val FAT_JAR_ALLOWED_PACKAGE_PREFIXES: List<String> = listOf(
    "org/jetbrains/lincheck/jvm/agent/wrapper/",
)

// After the fat jar is built, walks it and asserts two packaging invariants:
//
//   1. Package whitelist -- every `.class` entry falls under `FAT_JAR_ALLOWED_PACKAGE_PREFIXES`,
//      so the root exposes the wrapper and nothing else.
//      Bootstrap classes, the agent payload, the Kotlin runtime and third-party dependencies
//      all belong inside the nested jars, where the application classloader cannot reach them.
//
//   2. Nested jars -- both `bootstrap.jar` and `agent-payload.jar` are present as nested
//      resources at the fat-jar root, and never unpacked.
//
// A bootstrap class leaking to the root fails the first invariant:
// it would otherwise be resolvable both from there and from the nested `bootstrap.jar`,
// giving two definitions of the same class with diverging `static` state.
abstract class VerifyFatJarTask : DefaultTask() {
    @get:InputFile
    abstract val jar: RegularFileProperty

    @get:Input
    abstract val allowedPrefixes: ListProperty<String>

    @TaskAction
    fun verify() {
        val jarFile = jar.get().asFile
        ZipFile(jarFile).use { zip ->
            verifyPackages(jarFile, zip)
            verifyNestedJars(jarFile, zip)
        }
    }

    // Fails if any `.class` entry's package falls outside the whitelist.
    private fun verifyPackages(jarFile: File, zip: ZipFile) {
        val allowed = allowedPrefixes.get()
        val offenders = mutableListOf<String>()
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            if (!entry.name.endsWith(".class")) continue
            val logicalName = logicalName(entry.name)
            // `module-info.class` (or its versioned copy) has no package.
            if (logicalName == "module-info.class") continue
            if (allowed.none { logicalName.startsWith(it) }) {
                offenders += entry.name
            }
        }
        if (offenders.isNotEmpty()) {
            val sample = offenders.take(20).joinToString(LIST_SEP)
            val more = if (offenders.size > 20) "${LIST_SEP}...and ${offenders.size - 20} more" else ""
            val prefixes = allowed.joinToString(LIST_SEP)
            throw GradleException("""
                Fat jar ${jarFile.name} contains ${offenders.size} class file(s) outside the whitelist:
                  $sample$more
                Allowed package prefixes:
                  $prefixes
                Only wrapper classes may be visible from the javaagent jar root.
            """.trimIndent())
        }
    }

    private fun verifyNestedJars(jarFile: File, zip: ZipFile) {
        val missing = listOf("bootstrap.jar", "agent-payload.jar").filter { zip.getEntry(it) == null }
        if (missing.isNotEmpty()) {
            throw GradleException("""
                Fat jar ${jarFile.name} is missing nested entries: ${missing.joinToString()}.
                Both bootstrap and agent payload jars must stay nested at the javaagent jar root.
            """.trimIndent())
        }
    }

    // Separator for multi-line interpolated values (`$sample`, `$prefixes`) inside the trimIndent
    // templates above. The 18 leading spaces match the source indent of `  $sample` / `  $prefixes`
    // lines so trimIndent sees uniform indentation across all lines and strips it cleanly. Without
    // this, continuation lines would carry only 2 leading spaces and trimIndent would over-strip.
    private val LIST_SEP = "\n" + " ".repeat(18)

    // Strip the multi-release prefix so `META-INF/versions/9/foo/Bar.class`
    // is treated the same as `foo/Bar.class`.
    private fun logicalName(entryName: String): String =
        if (entryName.startsWith("META-INF/versions/")) {
            entryName.removePrefix("META-INF/versions/").substringAfter('/')
        } else {
            entryName
        }
}

// Below are tasks that are used by the tracing agent plugin.
// When these jars are loaded the `-Dlincheck.traceRecorderMode=true` VM argument is expected
fun Project.registerTraceAgentTasks(fatJarName: String, fatJarTaskName: String, premainClass: String) {
    // Ensure the Java plugin is applied (for sourceSets and runtimeClasspath)
    plugins.apply("java")

    val javaPluginExtension = extensions.getByType<JavaPluginExtension>()
    val mainSourceSet = javaPluginExtension.sourceSets.getByName("main")
    val runtimeClasspath = configurations.getByName("runtimeClasspath")
    val nestedJarsDir = layout.buildDirectory.dir("agent-nested-jars")
    val bootstrapBuildDir = project(":bootstrap").layout.buildDirectory

    val copyBootstrapJar = tasks.register<Copy>("copyBootstrapJar") {
        dependsOn(":bootstrapJar")
        from(bootstrapBuildDir.file("libs/bootstrap.jar"))
        into(nestedJarsDir)
    }

    val agentPayloadJar = tasks.register<Jar>("${fatJarTaskName}Payload") {
        destinationDirectory.set(nestedJarsDir)
        archiveFileName.set("agent-payload.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(mainSourceSet.output)
        from({
            runtimeClasspath.resolve().filter { it.name.endsWith(".jar") }.map { jar ->
                zipTree(jar).matching { exclude("bootstrap.jar") }
            }
        })
    }

    val agentWrapperJar = project(":jvm-agent-wrapper").layout.buildDirectory.file("libs/agent-wrapper.jar")
    val traceAgentFatJar = tasks.register<Jar>(fatJarTaskName) {
        archiveBaseName.set(fatJarName)
        archiveVersion.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        dependsOn(":jvm-agent-wrapper:jar", copyBootstrapJar, agentPayloadJar)
        from(zipTree(agentWrapperJar)) {
            exclude("META-INF/**")
        }
        // A plain Jar task keeps archive inputs nested without an intermediate wrapper archive.
        from(nestedJarsDir.map { it.file("bootstrap.jar") })
        from(agentPayloadJar.flatMap { it.archiveFile })

        manifest {
            appendMetaAttributes(project)
            attributes(
                mapOf(
                    "Premain-Class" to "org.jetbrains.lincheck.jvm.agent.wrapper.AgentWrapper",
                    "Agent-Class" to "org.jetbrains.lincheck.jvm.agent.wrapper.AgentWrapper",
                    "Tracing-Agent-Class" to premainClass,
                    "Can-Redefine-Classes" to "true",
                    "Can-Retransform-Classes" to "true"
                )
            )
        }
    }

    // After the fat jar is built, walk it and assert packaging invariants
    // (package whitelist + bootstrap nesting). See `VerifyFatJarTask`.
    val verifyFatJar = tasks.register<VerifyFatJarTask>("${fatJarTaskName}Verify") {
        jar.set(traceAgentFatJar.flatMap { it.archiveFile })
        allowedPrefixes.set(FAT_JAR_ALLOWED_PACKAGE_PREFIXES)
    }
    traceAgentFatJar.configure { finalizedBy(verifyFatJar) }
    tasks.named("check") { dependsOn(verifyFatJar) }

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
