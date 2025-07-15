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
import okhttp3.RequestBody.Companion.toRequestBody
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
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
    id("org.jetbrains.kotlinx.atomicfu")
    id("signing")
    id("maven-publish")
    id("org.jetbrains.dokka")
    id("kotlinx.team.infra") version "0.4.0-dev-80"
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
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

/*
 * Unfortunately, Lincheck was affected by the following bug in atomicfu
 * (at the latest version 0.27.0 at the time when this comment was written):
 * https://github.com/Kotlin/kotlinx-atomicfu/issues/525.
 *
 * To bypass the bug, the solution is to disable post-compilation JVM bytecode transformation
 * and enable only the JVM-IR transformation at the Kotlin compilation stage.
 *
 * See also https://github.com/JetBrains/lincheck/issues/668 for a more detailed description of the bug.
 */
atomicfu {
    transformJvm = false
}

sourceSets {
    main {
        java.srcDirs("src/jvm/main")
    }

    test {
        val jdkToolchainVersion: String by project

        java.srcDir("src/jvm/test")
        if (jdkToolchainVersion.toInt() >= 11) {
            java.srcDir("src/jvm/test-jdk11")
        } else {
            java.srcDir("src/jvm/test-jdk8")
        }

        // Tests that test classes from the bootstrap module `sun.nio.ch.lincheck` need to import these classes;
        // therefore, we need to add bootstrap to the compilation classpath.
        compileClasspath += files("${project(":bootstrap").layout.buildDirectory.get()}/classes/java/main")

        resources {
            srcDir("src/jvm/test/resources")
        }
    }

    dependencies {
        // main
        val kotlinVersion: String by project
        val kotlinxCoroutinesVersion: String by project
        val asmVersion: String by project
        val byteBuddyVersion: String by project
        val atomicfuVersion: String by project

        compileOnly(project(":bootstrap"))
        api(project(":common")) // TODO: `api` is used here in order to allow users of `lincheck` to access `LoggingLevel` enum class stored there,
                                //  but later this enum will be marked as deprecated and then hidden, after that `api` should be changed to `implementation`
        implementation(project(":jvm-agent"))
        implementation(project(":trace"))

        api("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
        api("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion")
        api("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
        api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
        api("org.ow2.asm:asm-commons:$asmVersion")
        api("org.ow2.asm:asm-util:$asmVersion")
        api("net.bytebuddy:byte-buddy:$byteBuddyVersion")
        api("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
        api("org.jetbrains.kotlinx:atomicfu:$atomicfuVersion")

        // test
        val junitVersion: String by project
        val jctoolsVersion: String by project
        val mockkVersion: String by project
        val gradleToolingApiVersion: String by project

        testImplementation("junit:junit:$junitVersion")
        testImplementation("org.jctools:jctools-core:$jctoolsVersion")
        testImplementation("io.mockk:mockk:${mockkVersion}")
        testImplementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
    }
}

tasks {
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain(project)
    }

    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain(project)
    }

    withType<KotlinCompile> {
        getAccessToInternalDefinitionsOf(project(":common"))
    }
}

tasks.withType<Test> {
    javaLauncher.set(
        javaToolchains.launcherFor {
            val jdkToolchainVersion: String by project
            val testInTraceDebuggerMode: String by project
            val jdkVersion = jdkToolchainVersion.toInt()
            // https://github.com/JetBrains/lincheck/issues/500
            val jreVersion = if (testInTraceDebuggerMode.toBoolean() && jdkVersion == 8) 17 else jdkVersion
            languageVersion.set(JavaLanguageVersion.of(jreVersion))
        }
    )
}

tasks {
    test {
        configureJvmTestCommon(project)

        val ideaActive = System.getProperty("idea.active") == "true"
        if (!ideaActive) {
            // We need to be able to run these tests in IntelliJ IDEA.
            // Unfortunately, the current Gradle support doesn't detect
            // the `testIsolated` and `trace[Debugger/Recorder]IntegrationTest` tasks.
            exclude("**/*IsolatedTest*")
        }
        // Do not run JdkUnsafeTraceRepresentationTest on Java 12 or earlier,
        // as this test relies on specific ConcurrentHashMap implementation.
        val jdkToolchainVersion: String by project
        if (jdkToolchainVersion.toInt() < 13) {
            exclude("**/*JdkUnsafeTraceRepresentationTest*")
        }
        val runAllTestsInSeparateJVMs: String by project
        forkEvery = when {
            runAllTestsInSeparateJVMs.toBoolean() -> 1
            // When running `jvmTest` from IntelliJ IDEA, we need to
            // be able to run `*IsolatedTest`s and isolate these tests
            // some way. Running all the tests in separate VM instances
            // significantly slows down the build. Therefore, we run
            // several tests in the same VM instance instead, trying
            // to balance between slowing down the build because of launching
            // new VM instances periodically and slowing down the build
            // because of the hanging threads in the `*IsolatedTest` ones.
            ideaActive -> 10
            else -> 0
        }
    }

    val testIsolated = register<Test>("testIsolated") {
        group = "verification"
        include("**/*IsolatedTest*")

        testClassesDirs = test.get().testClassesDirs
        classpath = test.get().classpath

        configureJvmTestCommon(project)

        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called

        forkEvery = 1
    }

    check {
        dependsOn += testIsolated
    }
}

val bootstrapJar = tasks.register<Copy>("bootstrapJar") {
    dependsOn(":bootstrap:jar")
    from(file("${project(":bootstrap").layout.buildDirectory.get()}/libs/bootstrap.jar"))
    into(file("${project(":jvm-agent").layout.buildDirectory.get()}/resources/main"))
}

val jar = tasks.named<Jar>("jar") {
    from(sourceSets["main"].output)
    dependsOn(tasks.compileJava, tasks.compileKotlin)
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    from(sourceSets["main"].allSource)
    // Also collect sources for the injected classes to simplify debugging
    from(project(":bootstrap").file("src"))
    archiveClassifier.set("sources")
}

val javadocJar = createJavadocJar("src/jvm/main")

tasks.withType<Jar> {
    dependsOn(":bootstrapJar")

    manifest {
        appendMetaAttributes(project)
    }
}

tasks.named("processResources").configure {
    dependsOn(":bootstrapJar")
}

publishing {
    publications {
        register("maven", MavenPublication::class) {
            val groupId: String by project
            val artifactId: String by project
            val version: String by project

            this.groupId = groupId
            this.artifactId = artifactId
            this.version = version

            from(components["kotlin"])
            artifact(sourcesJar)
            artifact(javadocJar)

            configureMavenPublication {
                name.set(artifactId)
                description.set("Lincheck - framework for testing concurrent code on the JVM")
            }
        }
    }

    configureRepositories(
        artifactsRepositoryUrl = uri(layout.buildDirectory.dir("artifacts/maven"))
    )
}

tasks.named("generateMetadataFileForMavenPublication") {
    dependsOn(jar)
    dependsOn(sourcesJar)
    dependsOn(javadocJar)
}

signing {
    val isUnderTeamCity = (System.getenv("TEAMCITY_VERSION") != null)
    if (isUnderTeamCity) {
        configureSigning()
        sign(publishing.publications)
    }
}

tasks {
    val publishToSpacePackages by registering {
        group = "publishing"
        println("Publishing all artifacts to Space Packages repository...")
        dependsOn(
            ":common:publishMavenPublicationToSpacePackagesRepository",
            ":jvm-agent:publishMavenPublicationToSpacePackagesRepository",
            ":trace:publishMavenPublicationToSpacePackagesRepository",
            ":publishMavenPublicationToSpacePackagesRepository"
        )
    }

    // publishing trace artifact only (and its dependencies) required as IntelliJ plugin dependencies
    val publishTraceArtifactToSpacePackages by registering {
        group = "publishing"
        println("Publishing all artifacts to Space Packages repository...")
        dependsOn(
            ":common:publishMavenPublicationToSpacePackagesRepository",
            ":trace:publishMavenPublicationToSpacePackagesRepository",
        )
    }

    val packSonatypeCentralBundle by registering(Zip::class) {
        group = "publishing"

        dependsOn(":common:publishMavenPublicationToArtifactsRepository")
        dependsOn(":jvm-agent:publishMavenPublicationToArtifactsRepository")
        dependsOn(":trace:publishMavenPublicationToArtifactsRepository")
        dependsOn(":publishMavenPublicationToArtifactsRepository")

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

    val queryCentralPortalPublicationStatus by registering {
        group = "publishing"

        doLast {
            val uriBase = "https://central.sonatype.com/api/v1/publisher/status"
            val deploymentId = rootProject.extra["deploymentId"] as String?
                ?: error("deploymentId is not set up")
            val uri = "$uriBase?id=$deploymentId"

            val userName = System.getenv("MVN_CLIENT_USERNAME")
            val token = System.getenv("MVN_CLIENT_PASSWORD")
            if (userName == null || token == null) {
                logger.error("Sonatype central portal credentials are not set up, skipping querying publication status")
                return@doLast
            }

            val base64Auth = Base64.getEncoder().encode("$userName:$token".toByteArray()).toString(Charsets.UTF_8)

            println("Sending request to $uri...")

            val client = OkHttpClient()
            val request = Request.Builder()
                .url(uri)
                .header("Authorization", "Bearer $base64Auth")
                .post("".toRequestBody())
                .build()
            client.newCall(request).execute().use { response ->
                val statusCode = response.code
                println("Query status code: $statusCode")
                println("Response: ${response.body!!.string()}")
                if (statusCode != 200) {
                    error("Query error to Central repository. Status code $statusCode.")
                }
            }
        }
    }
}