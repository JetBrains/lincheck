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

class GithubProjectSnapshot(val organization: String = "Kotlin", val repositoryName: String, val commitHash: String)

private val projectsToTest = listOf(
    GithubProjectSnapshot(
        repositoryName = "kotlinx.collections.immutable",
        commitHash = "592f05fce02a1ad9e26cc6f3fdb55cdd97910599"
    )
)

lateinit var traceDebuggerIntegrationTestsPrerequisites: TaskProvider<Task>

fun Project.registerIntegrationTestsPrerequisites() {
    val prerequisite = projectsToTest.map { projectToTest ->
        val projectName = projectToTest.repositoryName
        val hash = projectToTest.commitHash

        val downloadIntegrationTestsDependency = tasks.register<Download>("download_${projectName}_ForTest") {
            src("https://github.com/${projectToTest.organization}/$projectName/archive/$hash.zip")
            dest(layout.buildDirectory.dir("integrationTestProjects").get().file("$projectName-$hash.zip"))
            overwrite(false)
        }

        tasks.register<Copy>("${projectName}_unzip") {
            dependsOn(downloadIntegrationTestsDependency)
            from(zipTree(downloadIntegrationTestsDependency.get().dest))
            into(layout.buildDirectory.dir("integrationTestProjects"))

            eachFile {
                val correctPath = listOf(projectName) + relativePath.segments.drop(1)
                relativePath = RelativePath(file.isFile, *correctPath.toTypedArray())
            }
            includeEmptyDirs = false
        }
    }

    traceDebuggerIntegrationTestsPrerequisites = tasks.register("traceDebuggerIntegrationTestsPrerequisites") {
        prerequisite.forEach { dependsOn(it) }
        dependsOn("traceDebuggerFatJar")
    }
}