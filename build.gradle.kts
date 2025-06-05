import groovy.util.*
import kotlinx.team.infra.*
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// atomicfu
// buildscript {
//     val atomicfuVersion: String by project
//     val asmVersion: String by project
//     dependencies {
//         classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicfuVersion") {
//             classpath("org.ow2.asm:asm-commons:$asmVersion")
//             classpath("org.ow2.asm:asm-util:$asmVersion")
//         }
//     }
// }
// apply(plugin = "kotlinx-atomicfu")

plugins {
    java
    kotlin("jvm")
    id("org.jetbrains.kotlinx.atomicfu")
    id("maven-publish")
    id("kotlinx.team.infra") version "0.4.0-dev-80"
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
}

fun SourceDirectorySet.configureTestSources() {
    srcDir("src/jvm/test")
    srcDir("src/jvm/test-trace-debugger-integration")

    val jdkToolchainVersion: String by project
    if (jdkToolchainVersion.toInt() >= 11) {
        srcDir("src/jvm/test-jdk11")
    } else {
        srcDir("src/jvm/test-jdk8")
    }
}

// kotlin {
//     @OptIn(ExperimentalKotlinGradlePluginApi::class)
//     compilerOptions {
//         allWarningsAsErrors = true
//     }
// }

    // jvm {
    //     withJava()
    //
    //     val main by compilations.getting
    //     val test by compilations.getting
    //     val integrationTest by compilations.creating {
    //         defaultSourceSet {
    //             associateWith(main)
    //             associateWith(test)
    //         }
    //     }
    // }

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

        compileClasspath += files("${project(":bootstrap").buildDir}/classes/java/main")
            // project(":bootstrap").sourceSets.main.get().output

        resources {
            srcDir("src/jvm/test/resources")
        }
    }

    // create("integrationTest") {
    //     java.srcDir("src/jvm/test-integration")
    //     configureClasspath()
    // }
    //
    // create("traceDebuggerTest") {
    //     java.srcDir("src/jvm/test-trace-debugger-integration")
    //     configureClasspath()
    //
    //     resources {
    //         srcDir("src/jvm/test-trace-debugger-integration/resources")
    //     }
    // }

    dependencies {
        val kotlinVersion: String by project
        val kotlinxCoroutinesVersion: String by project
        val asmVersion: String by project
        val byteBuddyVersion: String by project
        val atomicfuVersion: String by project

        main {
            compileOnly(project(":bootstrap"))
            api("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
            api("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion")
            api("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
            api("org.ow2.asm:asm-commons:$asmVersion")
            api("org.ow2.asm:asm-util:$asmVersion")
            api("net.bytebuddy:byte-buddy:$byteBuddyVersion")
            api("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
            api("org.jetbrains.kotlinx:atomicfu:$atomicfuVersion")
        }

        val junitVersion: String by project
        val jctoolsVersion: String by project
        val mockkVersion: String by project
        val gradleToolingApiVersion: String by project

        test {
            compileOnly(project(":bootstrap"))
            // implementation(project(":bootstrap"))
            implementation("junit:junit:$junitVersion")
            implementation("org.jctools:jctools-core:$jctoolsVersion")
            implementation("io.mockk:mockk:${mockkVersion}")
        }

        // sourceSets.named("integrationTest") {
        //     implementation(rootProject)
        //     implementation("junit:junit:$junitVersion")
        // }
        //
        // sourceSets.named("traceDebuggerTest") {
        //     implementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
        //     runtimeOnly("org.slf4j:slf4j-simple:1.7.10")
        // }
    }
}

fun SourceSet.configureClasspath() {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

fun JavaCompile.setupJavaToolchain() {
    val jdkToolchainVersion: String by project
    javaToolchains {
        javaCompiler = compilerFor {
            languageVersion.set(JavaLanguageVersion.of(jdkToolchainVersion))
        }
    }
}

fun KotlinCompile.setupKotlinToolchain() {
    val jdkToolchainVersion: String by project
    kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(jdkToolchainVersion))
    })
}

tasks {
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain()
    }

    // named<JavaCompile>("compileIntegrationTestJava") {
    //     setupJavaToolchain()
    // }

    // named<KotlinCompile>("compileTestKotlinJvm") {
    //     setupKotlinToolchain()
    // }
    // named<KotlinCompile>("compileIntegrationTestKotlinJvm") {
    //     setupKotlinToolchain()
    // }
}

/*
 * We were unfortunate enough to be affected by several bugs of the ` atomicfu ` compiler plugin.
 *
 * - When using JVM-only Kotlin gradle plugin with atomicfu version 0.20.2 we hit the following bug:
 *   https://github.com/Kotlin/kotlinx-atomicfu/issues/301, resolved by
 *   https://github.com/Kotlin/kotlinx-atomicfu/pull/303
 *
 * - When using JVM-only Kotlin gradle plugin with atomicfu versions 0.21.0 <= ... < 0.23.2 we hit the following bug:
 *   https://github.com/Kotlin/kotlinx-atomicfu/issues/388, resolved by
 *   https://github.com/Kotlin/kotlinx-atomicfu/pull/394
 *
 * - For both JVM and Multiplatform gradle plugins and for atomicfu versions >= 0.23.2 we hit the following bug:
 *   https://github.com/Kotlin/kotlinx-atomicfu/issues/525, still open.
 *
 * To use the latest available atomicfu version 0.27.0 (at the moment when this comment was written)
 * and mitigate the bug, the solution is to disable post-compilation JVM bytecode transformation
 * and enable only the JVM-IR transformation at the Kotlin sources compilation stage.
 */
atomicfu {
    transformJvm = false
}

val bootstrapJar = tasks.register<Copy>("bootstrapJar") {
    dependsOn(":bootstrap:jar")
    from(file("${project(":bootstrap").buildDir}/libs/bootstrap.jar"))
    into(file("$buildDir/resources/main"))
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
    named("processResources").configure {
        dependsOn(bootstrapJar)
    }

    val sourcesJar = register<Jar>("sourcesJar") {
        from(sourceSets["main"].allSource)
        // Also collect sources for the injected classes to simplify debugging
        from(project(":bootstrap").file("src"))
    }

    fun Test.configureJvmTestCommon() {
        maxParallelForks = 1
        maxHeapSize = "6g"

        val instrumentAllClasses: String by project
        if (instrumentAllClasses.toBoolean()) {
            systemProperty("lincheck.instrumentAllClasses", "true")
        }
        // The `overwriteRepresentationTestsOutput` flag is used to automatically repair representation tests.
        // Representation tests work by comparing an actual output of the test (execution trace in most cases)
        // with the expected output stored in a file (which is kept in resources).
        // Normally, if the actual and expected outputs differ, the test fails,
        // but when this flag is set, instead the expected output is overwritten with the actual output.
        // This helps to quickly repair the tests when the output difference is non-essential
        // or when the output logic actually has changed in the code.
        // The test system relies on that the gradle test task is run from the root directory of the project,
        // to search for the directory where the expected output files are stored.
        //
        // PLEASE USE CAREFULLY: always first verify that the changes in the output are expected!
        val overwriteRepresentationTestsOutput: String by project
        if (overwriteRepresentationTestsOutput.toBoolean()) {
            systemProperty("lincheck.overwriteRepresentationTestsOutput", "true")
        }
        val extraArgs = mutableListOf<String>()
        val withEventIdSequentialCheck: String by project
        if (withEventIdSequentialCheck.toBoolean()) {
            extraArgs.add("-Dlincheck.debug.withEventIdSequentialCheck=true")
        }
        val testInTraceDebuggerMode: String by project
        if (testInTraceDebuggerMode.toBoolean()) {
            extraArgs.add("-Dlincheck.traceDebuggerMode=true")
        }
        val dumpTransformedSources: String by project
        if (dumpTransformedSources.toBoolean()) {
            extraArgs.add("-Dlincheck.dumpTransformedSources=true")
        }
        extraArgs.add("-Dlincheck.version=$version")

        findProperty("lincheck.logFile")?.let { extraArgs.add("-Dlincheck.logFile=${it as String}") }
        findProperty("lincheck.logLevel")?.let { extraArgs.add("-Dlincheck.logLevel=${it as String}") }

        jvmArgs(extraArgs)
    }

    // registerIntegrationTestsPrerequisites()

    test {
        val ideaActive = System.getProperty("idea.active") == "true"
        if (!ideaActive) {
            // We need to be able to run these tests in IntelliJ IDEA.
            // Unfortunately, the current Gradle support doesn't detect
            // the `jvmTestIsolated` and `traceDebuggerIntegrationTest` tasks.
            exclude("**/*IsolatedTest*")
            exclude("org/jetbrains/kotlinx/trace_debugger/integration/*")
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

    val testIsolated = register<Test>("testIsolated") {
        group = "verification"
        testClassesDirs = test.get().testClassesDirs
        classpath = test.get().classpath
        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called
        include("**/*IsolatedTest*")
        configureJvmTestCommon()
        forkEvery = 1

        // dependencies {
        //     compileOnly(project(":bootstrap"))
        // }
    }

    // val integrationTest = register<Test>("integrationTest") {
    //     group = "verification"
    //     testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    //     classpath = sourceSets["integrationTest"].runtimeClasspath
    //     enableAssertions = true
    //     testLogging.showStandardStreams = true
    //     outputs.upToDateWhen { false } // Always run tests when called
    //     configureJvmTestCommon()
    // }
    //
    // val traceDebuggerIntegrationTest = register<Test>("traceDebuggerIntegrationTest") {
    //     dependsOn(traceDebuggerIntegrationTestsPrerequisites)
    //     outputs.upToDateWhen { false } // Always run tests when called
    //     include("org/jetbrains/kotlinx/trace_debugger/integration/*")
    // }

    // check {
    //     dependsOn += testIsolated
    //     setDependsOn(dependsOn.filter { it != integrationTest })
    // }

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

// tasks.named("transformAtomicfuClasses") {
//     // Make sure this task runs after classes are compiled but before jar is created
//     dependsOn("compileKotlinJvm")
//     // Remove any direct dependencies on jar task
// }


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

// registerTraceDebuggerTasks()