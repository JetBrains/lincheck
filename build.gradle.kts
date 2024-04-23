import groovy.util.*
import kotlinx.team.infra.*
import org.gradle.jvm.tasks.Jar

// atomicfu
buildscript {
    val atomicfuVersion: String by project
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicfuVersion")
    }
}
apply(plugin = "kotlinx-atomicfu")

plugins {
    java
    kotlin("multiplatform")
    id("maven-publish")
    id("kotlinx.team.infra") version "0.4.0-dev-80"
}

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        withJava()

        val main by compilations.getting {
            kotlinOptions.jvmTarget = "11"
        }

        val test by compilations.getting {
            kotlinOptions.jvmTarget = "11"
        }
    }

    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir("src/jvm/main")

            val kotlinVersion: String by project
            val kotlinxCoroutinesVersion: String by project
            val asmVersion: String by project
            val byteBuddyVersion: String by project
            dependencies {
                compileOnly(project(":bootstrap"))
                api("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
                api("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion")
                api("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
                api("org.ow2.asm:asm-commons:$asmVersion")
                api("org.ow2.asm:asm-util:$asmVersion")
                api("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                api("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
            }
        }

        val jvmTest by getting {
            kotlin.srcDir("src/jvm/test")

            val junitVersion: String by project
            val jctoolsVersion: String by project
            val mockkVersion: String by project
            dependencies {
                implementation(project(":bootstrap"))
                implementation("junit:junit:$junitVersion")
                implementation("org.jctools:jctools-core:$jctoolsVersion")
                implementation("io.mockk:mockk:${mockkVersion}")
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    toolchain {
        val jdkToolchainVersion: String by project
        languageVersion = JavaLanguageVersion.of(jdkToolchainVersion)
    }
}

sourceSets.main {
    java.srcDirs("src/jvm/main")
}

sourceSets.test {
    java.srcDirs("src/jvm/test")
    resources {
        srcDir("src/jvm/test/resources")
    }
}

val bootstrapJar = tasks.register<Copy>("bootstrapJar") {
    dependsOn(":bootstrap:jar")
    from(file("${project(":bootstrap").buildDir}/libs/bootstrap.jar"))
    into(file("$buildDir/processedResources/jvm/main"))
}

tasks {
    named("processResources").configure {
        dependsOn(bootstrapJar)
    }

    replace("jvmSourcesJar", Jar::class).run {
        from(sourceSets["main"].allSource)
    }

    fun Test.configureJvmTestCommon() {
        maxParallelForks = 1
        maxHeapSize = "6g"
        val instrumentAllClassesInModelCheckingMode: String by project
        if (instrumentAllClassesInModelCheckingMode.toBoolean()) {
            systemProperty("lincheck.instrumentAllClassesInModelCheckingMode", "true")
        }
        val extraArgs = mutableListOf<String>()
        val withEventIdSequentialCheck: String by project
        if (withEventIdSequentialCheck.toBoolean()) {
            extraArgs.add("-Dlincheck.debug.withEventIdSequentialCheck=true")
        }
        jvmArgs(extraArgs)
    }

    val jvmTest = named<Test>("jvmTest") {
        val ideaActive = System.getProperty("idea.active") == "true"
        if (!ideaActive) {
            // We need to be able to run these tests in IntelliJ IDEA.
            // Unfortunately, the current Gradle support doesn't detect
            // the `jvmTestIsolated` task.
            exclude("**/*IsolatedTest*")
        }
        // Do not run JdkUnsafeTraceRepresentationTest on Java 12 or earlier,
        // as this test relies on specific ConcurrentHashMap implementation.
        val jdkToolchainVersion: String by project
        if (jdkToolchainVersion.toInt() < 13) {
            exclude("**/*JdkUnsafeTraceRepresentationTest*")
        }
        configureJvmTestCommon()
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

    val jvmTestIsolated = register<Test>("jvmTestIsolated") {
        group = jvmTest.get().group
        testClassesDirs = jvmTest.get().testClassesDirs
        classpath = jvmTest.get().classpath
        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called
        include("**/*IsolatedTest*")
        configureJvmTestCommon()
        forkEvery = 1
    }

    check {
        dependsOn(jvmTestIsolated)
    }

    withType<Jar> {
        dependsOn(bootstrapJar)
        manifest {
            val inceptionYear: String by project
            val lastCopyrightYear: String by project
            val version: String by project
            attributes(
                "Copyright" to
                        "Copyright (C) 2015 - 2019 Devexperts, LLC\n                                " +
                        "Copyright (C) $inceptionYear - $lastCopyrightYear JetBrains, s.r.o.",
                // This attribute let us get the version from the code.
                "Implementation-Version" to version
            )
        }
    }
}

infra {
    teamcity {
        val name: String by project
        val version: String by project
        libraryStagingRepoDescription = "$name $version"
    }
    publishing {
        include(":")

        libraryRepoUrl = "https://github.com/Kotlin/kotlinx-lincheck"
        sonatype {}
    }
}

publishing {
    project.establishSignDependencies()
}

fun Project.establishSignDependencies() {
    // Sign plugin issues and publication:
    // Establish dependency between 'sign' and 'publish*' tasks.
    tasks.withType<AbstractPublishToMaven>().configureEach {
        dependsOn(tasks.withType<Sign>())
    }
}

mavenPublicationsPom {
    description.set("Lincheck - Framework for testing concurrent data structures")
    val licenceName = "Mozilla Public License Version 2.0"
    licenses {
        license {
            name.set(licenceName)
            url.set("https://www.mozilla.org/en-US/MPL/2.0/")
            distribution.set("repo")
        }
    }
    withXml {
        removeAllLicencesExceptOne(licenceName)
    }
}

// kotlinx.team.infra adds Apache License, Version 2.0, remove it manually
fun XmlProvider.removeAllLicencesExceptOne(licenceName: String) {
    val licenseList = (asNode()["licenses"] as NodeList)[0] as Node
    val licenses = licenseList["license"] as NodeList
    licenses.filterIsInstance<Node>().forEach { licence ->
        val name = (licence["name"] as NodeList)[0] as Node
        val nameValue = (name.value() as NodeList)[0] as String
        if (nameValue != licenceName) {
            licenseList.remove(licence)
        }
    }
}
