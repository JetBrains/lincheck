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
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.dokka.gradle.DokkaTask
import org.gradle.kotlin.dsl.*

fun Project.createJavadocJar(archiveName: String = this.name): TaskProvider<Jar> {
    val dokkaHtml = tasks.named<DokkaTask>("dokkaHtml") {
        outputDirectory.set(file("${layout.buildDirectory.get()}/javadoc"))
        dokkaSourceSets {
            named("main") {
                sourceRoots.from(file("src/main"))
                reportUndocumented.set(false)
            }
        }
    }

    val javadocJar = tasks.register<Jar>("javadocJar") {
        archiveBaseName.set(archiveName)
        dependsOn(dokkaHtml)
        from("${layout.buildDirectory.get()}/javadoc")
        archiveClassifier.set("javadoc")
    }

    return javadocJar
}