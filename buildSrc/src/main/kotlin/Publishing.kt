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
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

// Borrowed from https://github.com/Kotlin/kotlinx.team.infra/.
// Currently we re-use the kotlin libraries key for signing.
fun Project.configureSigning() {
    project.pluginManager.apply(SigningPlugin::class.java)

    val keyId = getProperty("libs.sign.key.id")
    val signingKey = getProperty("libs.sign.key.private")
    val signingKeyPassphrase = getProperty("libs.sign.passphrase")

    project.extensions.configure<SigningExtension>("signing") {
        useInMemoryPgpKeys(keyId, signingKey, signingKeyPassphrase)
        val signingTasks = sign(extensions.getByType(PublishingExtension::class.java).publications) // all publications
        // due to each publication including the same javadoc artifact file,
        // every publication signing task produces (overwrites) the same javadoc.asc signature file beside
        // and includes it to that publication
        // Thus, every publication publishing task implicitly depends on every signing task
        tasks.withType(AbstractPublishToMaven::class.java).configureEach {
            dependsOn(signingTasks) // make this dependency explicit
        }
    }
}

private fun Project.getProperty(name: String): String =
    findProperty(name) as? String ?: error("Property `$name` is not specified, artifact signing is not enabled.")