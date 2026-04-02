/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.*

buildscript {
    repositories {
        maven { url = uri("https://packages.jetbrains.team/maven/p/jcs/maven") }
    }
    dependencies {
        classpath("com.squareup.okhttp3:okhttp:4.12.0")
    }
}

plugins {
    java
    kotlin("jvm")
    id("kotlinx.team.infra") version "0.4.0-dev-80"
}

repositories {
    mavenCentral()
}

kotlin {
    configureKotlin()
}

java {
    configureJava()
}

subprojects {
    plugins.apply("java")
    plugins.apply("org.jetbrains.kotlin.jvm")

    kotlin {
        configureKotlin()
    }

    java {
        configureJava()
    }
}

val bootstrapJar = tasks.register<Copy>("bootstrapJar") {
    dependsOn(":bootstrap:jar")
    from(file("${project(":bootstrap").layout.buildDirectory.get()}/libs/bootstrap.jar"))
    into(file("${project(":jvm-agent").layout.buildDirectory.get()}/resources/main"))
}

tasks {
    val publishToSpacePackages by registering {
        group = "publishing"
        println("Publishing all artifacts to Space Packages repository...")
        dependsOn(
            ":common:publishMavenPublicationToSpacePackagesRepository",
            ":jvm-agent:publishMavenPublicationToSpacePackagesRepository",
            ":trace:publishMavenPublicationToSpacePackagesRepository",
            ":lincheck:publishMavenPublicationToSpacePackagesRepository",

            // also publish java agents' fat jars
            ":trace-recorder:publishMavenPublicationToSpacePackagesRepository",
            ":live-debugger:publishMavenPublicationToSpacePackagesRepository",
        )
    }

    // publishing trace artifact only (and its dependencies) required as IntelliJ plugin dependencies
    val publishTraceArtifactToSpacePackages by registering {
        group = "publishing"
        println("Publishing trace artifacts to Space Packages repository...")
        dependsOn(
            ":common:publishMavenPublicationToSpacePackagesRepository",
            ":trace:publishMavenPublicationToSpacePackagesRepository",

            // also publish java agents' fat jars
            ":trace-recorder:publishMavenPublicationToSpacePackagesRepository",
            ":live-debugger:publishMavenPublicationToSpacePackagesRepository",
        )
    }

    val packSonatypeCentralBundle by registering(Zip::class) {
        group = "publishing"

        dependsOn(":common:publishMavenPublicationToArtifactsRepository")
        dependsOn(":jvm-agent:publishMavenPublicationToArtifactsRepository")
        dependsOn(":trace:publishMavenPublicationToArtifactsRepository")
        dependsOn(":lincheck:publishMavenPublicationToArtifactsRepository")

        from(layout.buildDirectory.dir("artifacts/maven"))
        archiveFileName.set("bundle.zip")
        destinationDirectory.set(layout.buildDirectory)
    }

    val publishMavenToCentralPortal by registering {
        group = "publishing"

        dependsOn(packSonatypeCentralBundle)

        doLast {
            val uriBase = "https://central.sonatype.com/api/v1/publisher/upload"
            val publishingType = "AUTOMATIC"
            val deploymentName = "${project.name}-$version"
            val uri = "$uriBase?name=$deploymentName&publishingType=$publishingType"

            val userName = System.getenv("MVN_CLIENT_USERNAME")
            val token = System.getenv("MVN_CLIENT_PASSWORD")
            if (userName == null || token == null) {
                logger.error("Sonatype central portal credentials are not set up, skipping publishing")
                return@doLast
            }

            val base64Auth = Base64.getEncoder().encode("$userName:$token".toByteArray()).toString(Charsets.UTF_8)
            val bundleFile = packSonatypeCentralBundle.get().archiveFile.get().asFile

            println("Sending request to $uri...")

            val client = OkHttpClient()
            val request = Request.Builder()
                .url(uri)
                .header("Authorization", "Bearer $base64Auth")
                .post(
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("bundle", bundleFile.name, bundleFile.asRequestBody())
                        .build()
                )
                .build()
            client.newCall(request).execute().use { response ->
                val statusCode = response.code
                println("Upload status code: $statusCode")
                println("Upload result: ${response.body!!.string()}")
                if (statusCode != 201) {
                    error("Upload error to Central repository. Status code $statusCode.")
                }
            }
        }
    }
}
