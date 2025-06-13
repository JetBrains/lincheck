import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
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

    create("integrationTest") {
        java.srcDir("src/jvm/test-integration")
        configureClasspath()
    }

    create("traceDebuggerTest") {
        java.srcDir("src/jvm/test-trace-debugger-integration")
        configureClasspath()

        resources {
            srcDir("src/jvm/test-trace-debugger-integration/resources")
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

        testImplementation("junit:junit:$junitVersion")
        testImplementation("org.jctools:jctools-core:$jctoolsVersion")
        testImplementation("io.mockk:mockk:${mockkVersion}")

        // integrationTest
        val integrationTestImplementation by configurations

        integrationTestImplementation(rootProject)
        integrationTestImplementation("junit:junit:$junitVersion")
        integrationTestImplementation("org.jctools:jctools-core:$jctoolsVersion")

        // traceDebuggerTest
        val gradleToolingApiVersion: String by project
        val traceDebuggerTestImplementation by configurations
        val traceDebuggerTestRuntimeOnly by configurations

        traceDebuggerTestImplementation("junit:junit:$junitVersion")
        traceDebuggerTestImplementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
        traceDebuggerTestRuntimeOnly("org.slf4j:slf4j-simple:1.7.10")
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

tasks {
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain()
    }

    named<JavaCompile>("compileIntegrationTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileIntegrationTestKotlin") {
        setupKotlinToolchain()
    }

    named<JavaCompile>("compileTraceDebuggerTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileTraceDebuggerTestKotlin") {
        setupKotlinToolchain()
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

// add an association to main and test modules to enable access to `internal` APIs inside integration tests:
// https://kotlinlang.org/docs/gradle-configure-project.html#associate-compiler-tasks
kotlin {
    target.compilations.named("integrationTest") {
        configureAssociation()
    }
    target.compilations.named("traceDebuggerTest") {
        configureAssociation()
    }
}

fun KotlinCompilation<*>.configureAssociation() {
    val main by target.compilations.getting
    val test by target.compilations.getting
    associateWith(main)
    associateWith(test)
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

    test {
        configureJvmTestCommon()

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

        configureJvmTestCommon()

        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called

        forkEvery = 1
    }

    val integrationTest = register<Test>("integrationTest") {
        group = "verification"

        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath

        configureJvmTestCommon()

        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called
    }

    registerTraceDebuggerIntegrationTestsPrerequisites()

    val traceDebuggerIntegrationTest = register<Test>("traceDebuggerIntegrationTest") {
        group = "verification"
        include("org/jetbrains/kotlinx/trace_debugger/integration/*")

        testClassesDirs = sourceSets["traceDebuggerTest"].output.classesDirs
        classpath = sourceSets["traceDebuggerTest"].runtimeClasspath

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceDebuggerIntegrationTestsPrerequisites)
    }

    check {
        dependsOn += testIsolated
    }
}

registerTraceDebuggerTasks()

val bootstrapJar = tasks.register<Copy>("bootstrapJar") {
    dependsOn(":bootstrap:jar")
    from(file("${project(":bootstrap").layout.buildDirectory.get()}/libs/bootstrap.jar"))
    into(file("${layout.buildDirectory.get()}/resources/main"))
}

val jar by tasks.getting(Jar::class) {
    from(sourceSets["main"].output)
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    from(sourceSets["main"].allSource)
    // Also collect sources for the injected classes to simplify debugging
    from(project(":bootstrap").file("src"))
}

tasks.withType<Jar> {
    dependsOn(bootstrapJar)

    manifest {
        val inceptionYear: String by project
        val lastCopyrightYear: String by project
        val version: String by project
        attributes(
            "Copyright" to
                    "Copyright (C) 2015 - 2019 Devexperts, LLC\n"
                    + " ".repeat(29) + // additional space to fill to the 72-character length of JAR manifest file
                    "Copyright (C) $inceptionYear - $lastCopyrightYear JetBrains, s.r.o.",
            // This attribute let us get the version from the code.
            "Implementation-Version" to version
        )
    }
}

tasks.named("processResources").configure {
    dependsOn(bootstrapJar)
}

publishing {
    publications {
        register("maven", MavenPublication::class) {
            val name: String by project
            val group: String by project
            val version: String by project

            this.artifactId = name
            this.groupId = group
            this.version = version

            from(components["kotlin"])
            artifact(sourcesJar.get()) {
                classifier = "sources"
            }

            pom {
                this.name.set(name)
                this.description.set("Lincheck - framework for testing concurrent data structures")

                licenses {
                    license {
                        this.name.set("Mozilla Public License Version 2.0")
                        this.url.set("https://www.mozilla.org/en-US/MPL/2.0/")
                        this.distribution.set("repo")
                    }
                }
            }
        }
    }
}

tasks.named("generateMetadataFileForMavenPublication") {
    dependsOn(sourcesJar)
}