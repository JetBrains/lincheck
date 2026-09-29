/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.Properties

/**
 * A real-world project the agents are tested against: the GitHub [repository] (`https://github.com/<org>/<name>`)
 * at [commitHash], fetched as a source archive and unpacked under `integrationTestProjects/<name>`.
 */
class GithubProjectSnapshot(val name: String, val repository: String, val commitHash: String) {
    val archiveUrl: String get() = "$repository/archive/$commitHash.zip"
    val archiveFileName: String get() = "$name-$commitHash.zip"
}

/** The pins' single home; see the comments there for the file's shape. */
private const val PROJECTS_MANIFEST = "integration-test/github-projects.properties"

/**
 * A directory of pre-fetched `<name>-<sha>.zip` archives, so a CI chain downloads each once instead of once per
 * build. Unset, or an archive missing from it (a stale cache after a pin bump), means download from GitHub.
 */
private const val ARCHIVES_CACHE_ENV_VAR = "LINCHECK_TEST_PROJECT_ARCHIVES"

private fun Project.projectsToTest(): List<GithubProjectSnapshot> {
    val manifest = rootProject.file(PROJECTS_MANIFEST)
    val pins = Properties().also { properties -> manifest.inputStream().use { properties.load(it) } }
    return pins.stringPropertyNames()
        .filter { it.endsWith(".repository") }
        .sorted()
        .map { key ->
            val name = key.removeSuffix(".repository")
            val ref = checkNotNull(pins.getProperty("$name.ref")?.trim()) { "$manifest has no '$name.ref'" }
            check(ref.matches(Regex("[0-9a-f]{40}"))) { "$manifest: '$name.ref' must be a full 40-char commit SHA, got '$ref'" }
            GithubProjectSnapshot(name, pins.getProperty(key).trim().removeSuffix(".git"), ref)
        }
}

/** Prepare the selected suite's external snapshots; null selects every registered repository. */
fun Project.registerTraceAgentIntegrationTestsPrerequisites(projectNames: Set<String>?): TaskProvider<Task> {
    val unzippedTestProjectsDir = layout.buildDirectory.dir("integrationTestProjects")
    val archivesCache = System.getenv(ARCHIVES_CACHE_ENV_VAR)?.takeIf { it.isNotBlank() }?.let(::File)
    val prerequisite = projectsToTest().filter { projectNames == null || it.name in projectNames }.map { projectToTest ->
        val projectName = projectToTest.name
        val hash = projectToTest.commitHash
        val cachedArchive = archivesCache?.resolve(projectToTest.archiveFileName)?.takeIf { it.isFile }

        val downloadIntegrationTestsDependency = tasks.register<Download>("download_${projectName}_ForTest") {
            src(projectToTest.archiveUrl)
            dest(unzippedTestProjectsDir.get().file(projectToTest.archiveFileName))
            overwrite(false) // TODO: seems to still overwrite for some reason
            onlyIf("no pre-fetched archive in \$$ARCHIVES_CACHE_ENV_VAR") { cachedArchive == null }
        }

        tasks.register<Copy>("${projectName}_unzip") {
            dependsOn(downloadIntegrationTestsDependency)
            if (cachedArchive != null) doFirst { logger.lifecycle("Reusing pre-fetched archive $cachedArchive") }
            from(zipTree(cachedArchive ?: downloadIntegrationTestsDependency.get().dest))
            // We set a unique destination folder for the unzip task.
            // Otherwise, Gradle thinks that we are trying to use the output of one unzip task as input for another.
            // Also, this helps to drop the commit hash from project folder.
            into(unzippedTestProjectsDir.get().dir(projectName))
            val checkout = unzippedTestProjectsDir.get().dir(projectName).asFile
            val revisionMarker = checkout.resolve(".integration-test-revision")
            // Preserve warm compiler outputs for the same snapshot, but remove deleted sources when its pin changes.
            doFirst {
                if (!revisionMarker.isFile || revisionMarker.readText() != hash) checkout.deleteRecursively()
            }
            doLast { revisionMarker.writeText(hash) }

            eachFile {
                val correctPath = relativePath.segments.drop(1)
                relativePath = RelativePath(file.isFile, *correctPath.toTypedArray())
            }
            includeEmptyDirs = false
        }
    }

    return tasks.register("traceAgentIntegrationTestsPrerequisites") {
        prerequisite.forEach { dependsOn(it) }
        dependsOn(":trace-recorder:traceRecorderFatJar")
        dependsOn(":live-debugger:liveDebuggerFatJar")
    }
}

/**
 * Directory names, under `integration-test/test-projects`, of the applications that ship their own,
 * agent-incompatible copy of one of the agent's own dependencies.
 */
private val classpathClashTestProjects = listOf(
    "kotlin-classpath-clash",
    "asm-classpath-clash",
)

/**
 * Stages the `*-classpath-clash` projects next to the downloaded integration-test projects.
 *
 * The projects are shared by the trace-recorder and live-debugger classpath-isolation tests,
 * so they live in `integration-test/test-projects` rather than in either consumer.
 */
fun Project.copyClasspathClashTestProjects(): List<TaskProvider<Copy>> =
    classpathClashTestProjects.map { projectName ->
        tasks.register<Copy>("${projectName}_copyTestProject") {
            from(rootProject.layout.projectDirectory.dir("integration-test/test-projects/$projectName"))
            into(layout.buildDirectory.dir("integrationTestProjects/$projectName"))
        }
    }

/**
 * This function is required to copy `trace-recorder-fat.jar` file from
 * the corresponding project into the lincheck's build directory. This allows integration tests to see
 * the fat-jar and add a path to it via `-javaagent` VM flag.
 *
 * @param fromProject trace-recorder project from which to copy the fat-jar.
 * @param fatJarName expected fat-jar name.
 * @param prerequisites preparation owned by this integration-test module.
 */
fun Project.copyTraceAgentFatJar(
    fromProject: Project,
    fatJarName: String,
    prerequisites: TaskProvider<Task>,
): TaskProvider<Copy> {
    val copyTraceAgentFatJar = tasks.register<Copy>("${fromProject.name}_copyAgentFatJar") {
        dependsOn(prerequisites)
        val fatJarFile = fromProject.layout.buildDirectory.file("libs/$fatJarName")
        from(fatJarFile)
        into(layout.buildDirectory.dir("libs"))
    }

    return copyTraceAgentFatJar
}
