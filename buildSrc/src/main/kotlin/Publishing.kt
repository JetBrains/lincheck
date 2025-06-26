/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.Project
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

fun MavenPublication.configureMavenPublication(configurePom: MavenPom.() -> Unit) {
    pom {
        configurePom()

        url.set("https://github.com/JetBrains/lincheck")
        scm {
            connection.set("scm:git:https://github.com/JetBrains/lincheck.git")
            url.set("https://github.com/JetBrains/lincheck")
        }

        // according to the reference, this should be the person(s) to be contacted about the project
        developers {
            developer {
                name.set("Nikita Koval")
                id.set("nikita.koval")
                email.set("nikita.koval@jetbrains.com")
                organization.set("JetBrains")
                organizationUrl.set("https://www.jetbrains.com")
            }
            developer {
                name.set("Evgeniy Moiseenko")
                id.set("evgeniy.moiseenko")
                email.set("evgeniy.moiseenko@jetbrains.com")
                organization.set("JetBrains")
                organizationUrl.set("https://www.jetbrains.com")
            }
            developer {
                name.set("Lev Serebryakov")
                id.set("lev.serebryakov")
                email.set("lev.serebryakov@jetbrains.com")
                organization.set("JetBrains")
                organizationUrl.set("https://www.jetbrains.com")
            }
        }

        licenses {
            license {
                name.set("Mozilla Public License Version 2.0")
                url.set("https://www.mozilla.org/en-US/MPL/2.0/")
                distribution.set("repo")
            }
        }
    }
}

// Borrowed from https://github.com/Kotlin/kotlinx.team.infra/.
// Currently we re-use the kotlin libraries key for signing.
fun Project.configureSigning() {
    project.pluginManager.apply(SigningPlugin::class.java)

    val keyId = getSigningProperty("libs.sign.key.id") ?: return
    val signingKey = getSigningProperty("libs.sign.key.private") ?: return
    val signingKeyPassphrase = getSigningProperty("libs.sign.passphrase") ?: return

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

private fun Project.getSigningProperty(name: String): String? =
    (findProperty(name) as? String).also {
        if (it == null) logger.warn("Property `$name` is not specified, artifact signing is not enabled.")
    }