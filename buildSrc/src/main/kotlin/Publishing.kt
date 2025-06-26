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

fun MavenPublication.configureMavenPublication(configurePom: MavenPom.() -> Unit) {
    pom {
        configurePom()

        url.set("https://github.com/JetBrains/lincheck")
        scm {
            connection.set("scm:git:https://github.com/JetBrains/lincheck.git")
            url.set("https://github.com/JetBrains/lincheck")
        }

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