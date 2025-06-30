import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths

plugins {
    java
    kotlin("jvm")
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

sourceSets {
    create("lincheckIntegrationTest") {
        java.srcDir("lincheck")
        configureClasspath()
    }

    create("traceDebuggerIntegrationTest") {
        java.srcDir("trace-debugger")
        configureClasspath()

        resources {
            srcDir("trace-debugger/resources")
        }
    }

    create("traceRecorderIntegrationTest") {
        java.srcDir("trace-recorder")
        configureClasspath()

        resources {
            srcDir("trace-recorder/resources")
        }
    }

    dependencies {
        testImplementation(rootProject.sourceSets["test"].output)

        // test
        val junitVersion: String by project
        val jctoolsVersion: String by project
        val mockkVersion: String by project
        val slf4jVersion: String by project
        val gradleToolingApiVersion: String by project

        testImplementation("junit:junit:$junitVersion")
        testImplementation("org.jctools:jctools-core:$jctoolsVersion")
        testImplementation("io.mockk:mockk:${mockkVersion}")
        testImplementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")

        // lincheckIntegrationTest
        val lincheckIntegrationTestImplementation by configurations

        lincheckIntegrationTestImplementation(rootProject)
        lincheckIntegrationTestImplementation("junit:junit:$junitVersion")
        lincheckIntegrationTestImplementation("org.jctools:jctools-core:$jctoolsVersion")

        // traceDebuggerIntegrationTest
        val traceDebuggerIntegrationTestImplementation by configurations
        val traceDebuggerIntegrationTestRuntimeOnly by configurations

        traceDebuggerIntegrationTestImplementation("junit:junit:$junitVersion")
        traceDebuggerIntegrationTestImplementation("io.mockk:mockk:${mockkVersion}") // transitively includes junit 5, which is needed to
                                                                                     // parse junit-5 '@Test' annotations from method definitions
        traceDebuggerIntegrationTestImplementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
        traceDebuggerIntegrationTestRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")

        // traceRecorderIntegrationTest
        val traceRecorderIntegrationTestImplementation by configurations
        val traceRecorderIntegrationTestRuntimeOnly by configurations

        traceRecorderIntegrationTestImplementation("junit:junit:$junitVersion")
        traceRecorderIntegrationTestImplementation("io.mockk:mockk:${mockkVersion}")
        traceRecorderIntegrationTestImplementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
        traceRecorderIntegrationTestRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")
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
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain()
    }

    named<JavaCompile>("compileLincheckIntegrationTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileLincheckIntegrationTestKotlin") {
        setupKotlinToolchain()
    }

    named<JavaCompile>("compileTraceDebuggerIntegrationTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileTraceDebuggerIntegrationTestKotlin") {
        setupKotlinToolchain()
    }

    named<JavaCompile>("compileTraceRecorderIntegrationTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileTraceRecorderIntegrationTestKotlin") {
        setupKotlinToolchain()
    }

    withType<KotlinCompile> {
        setupFriendPathsToRootProject()
    }
}

tasks {
    // TODO: rename to match trace-debugger/recorder gradle task naming pattern to 'lincheckIntegrationTest'
    val lincheckIntegrationTest = register<Test>("integrationTest") {
        group = "verification"

        testClassesDirs = sourceSets["lincheckIntegrationTest"].output.classesDirs
        classpath = sourceSets["lincheckIntegrationTest"].runtimeClasspath

        configureJvmTestCommon(project)

        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called
    }

    registerTraceAgentIntegrationTestsPrerequisites()

    val traceDebuggerIntegrationTest = register<Test>("traceDebuggerIntegrationTest") {
        configureJvmTestCommon(project)
        group = "verification"
        // TODO: do I need these explicit include's?
        include("org/jetbrains/trace_debugger_test/*")

        testClassesDirs = sourceSets["traceDebuggerIntegrationTest"].output.classesDirs
        classpath = sourceSets["traceDebuggerIntegrationTest"].runtimeClasspath

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
    }

    val traceRecorderIntegrationTest = register<Test>("traceRecorderIntegrationTest") {
        configureJvmTestCommon(project)
        group = "verification"
        include("org/jetbrains/trace_recorder_test/*")

        testClassesDirs = sourceSets["traceRecorderIntegrationTest"].output.classesDirs
        classpath = sourceSets["traceRecorderIntegrationTest"].runtimeClasspath

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
    }
}

// TODO: how not to copy these functions everywhere
fun JavaCompile.setupJavaToolchain() {
    val jdkToolchainVersion: String by project
    setupJavaToolchain(javaToolchains, jdkToolchainVersion)
}

fun KotlinCompile.setupKotlinToolchain() {
    val jdkToolchainVersion: String by project
    setupKotlinToolchain(javaToolchains, jdkToolchainVersion)
}

fun SourceSet.configureClasspath() {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output

    // Add the root project's source sets to allow access to internal classes later via friend paths
    compileClasspath += rootProject.sourceSets.main.get().output + rootProject.sourceSets.test.get().output
    runtimeClasspath += rootProject.sourceSets.main.get().output + rootProject.sourceSets.test.get().output
}

fun KotlinCompile.setupFriendPathsToRootProject() {
    val mainSourceSet = rootProject.sourceSets.main.get().output.files
    val testSourceSet = rootProject.sourceSets.test.get().output.files
    val rootJarArchive = Paths.get(rootProject.buildDir.absolutePath, "libs", rootProject.name + "-" + rootProject.version + ".jar").toFile()
    friendPaths.setFrom(friendPaths.files + mainSourceSet + testSourceSet + rootJarArchive)
}