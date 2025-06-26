/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun KotlinJvmProjectExtension.configureKotlin() {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

fun JavaPluginExtension.configureJava() {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

fun JavaCompile.setupJavaToolchain(javaToolchains: JavaToolchainService,  jdkToolchainVersion: String) {
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(jdkToolchainVersion))
    })
}

fun KotlinCompile.setupKotlinToolchain(javaToolchains: JavaToolchainService, jdkToolchainVersion: String) {
    kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(jdkToolchainVersion))
    })
}