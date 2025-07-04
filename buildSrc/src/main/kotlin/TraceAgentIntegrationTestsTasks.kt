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

class GithubProjectSnapshot(val organization: String, val repositoryName: String, val commitHash: String)

private val projectsToTest = listOf(
    GithubProjectSnapshot(
        organization = "Kotlin",
        repositoryName = "kotlinx.collections.immutable",
        commitHash = "592f05fce02a1ad9e26cc6f3fdb55cdd97910599"
    ),
    GithubProjectSnapshot(
        organization = "ivandev0",
        repositoryName = "TraceDebuggerExamples",
        commitHash = "e3f39b1cb9dd8b3b4942015d57ef5ce0f5f37c6b"
    )
)

lateinit var traceAgentIntegrationTestsPrerequisites: TaskProvider<Task>

fun Project.registerTraceAgentIntegrationTestsPrerequisites() {
    val unzippedTestProjectsDir = layout.buildDirectory.dir("integrationTestProjects")
    val prerequisite = projectsToTest.map { projectToTest ->
        val projectName = projectToTest.repositoryName
        val hash = projectToTest.commitHash

        val downloadIntegrationTestsDependency = tasks.register<Download>("download_${projectName}_ForTest") {
            src("https://github.com/${projectToTest.organization}/$projectName/archive/$hash.zip")
            dest(unzippedTestProjectsDir.get().file("$projectName-$hash.zip"))
            overwrite(false) // TODO: seems to still overwrite for some reason
        }

        tasks.register<Copy>("${projectName}_unzip") {
            dependsOn(downloadIntegrationTestsDependency)
            from(zipTree(downloadIntegrationTestsDependency.get().dest))
            // We set a unique destination folder for the unzip task.
            // Otherwise, Gradle thinks that we are trying to use the output of one unzip task as input for another.
            // Also, this helps to drop the commit hash from project folder.
            into(unzippedTestProjectsDir.get().dir(projectName))

            eachFile {
                val correctPath = relativePath.segments.drop(1)
                relativePath = RelativePath(file.isFile, *correctPath.toTypedArray())
            }
            includeEmptyDirs = false
        }
    }

    traceAgentIntegrationTestsPrerequisites = tasks.register("traceAgentIntegrationTestsPrerequisites") {
        prerequisite.forEach { dependsOn(it) }
        dependsOn(":jvm-agent:traceAgentFatJar")
    }
}