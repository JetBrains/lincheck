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
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import java.net.URI

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

fun PublishingExtension.configureRepositories(artifactsRepositoryUrl: URI) {
    repositories {
        // set up a local directory publishing for further signing and uploading to sonatype
        maven {
            name = "artifacts"
            url = artifactsRepositoryUrl
        }

        // legacy sonatype staging publishing
        maven {
            name = "sonatypeStaging"
            url = URI("https://oss.sonatype.org/service/local/staging/deploy/maven2/")

            credentials {
                username = System.getenv("libs.sonatype.user")
                password = System.getenv("libs.sonatype.password")
            }
        }

        // space-packages publishing
        maven {
            name = "spacePackages"
            url = URI("https://packages.jetbrains.team/maven/p/concurrency-tools/maven")

            credentials {
                username = System.getenv("SPACE_USERNAME")
                password = System.getenv("SPACE_PASSWORD")
            }
        }
    }
}