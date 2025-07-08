/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    val kotlinVersion: String by project
    val dokkaVersion: String by project

    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:$dokkaVersion")
    implementation("de.undercouch.download:de.undercouch.download.gradle.plugin:5.6.0")

    implementation(gradleApi())
    implementation(gradleKotlinDsl())
    implementation(kotlin("gradle-plugin"))
    implementation("org.gradle.kotlin:gradle-kotlin-dsl-plugins:4.2.1")  // Add this line
}
