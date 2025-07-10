import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths

plugins {
    java
    kotlin("jvm")
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

    repositories {
        mavenCentral()
    }

    kotlin {
        configureKotlin()
    }

    java {
        configureJava()
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
        withType<JavaCompile> {
            setupJavaToolchain(project)
        }

        withType<KotlinCompile> {
            setupKotlinToolchain(project)
            getAccessToInternalDefinitionsOf(rootProject)
        }
    }
}

//sourceSets {
//    create("common") {
//        java.srcDir("common")
//        configureAccessToRootProject()
//    }
//
//    create("lincheckIntegrationTest") {
//        java.srcDir("lincheck")
//        configureClasspath()
//    }
//
//    create("traceDebuggerIntegrationTest") {
//        java.srcDir("trace-debugger")
//        configureClasspath()
//
//        resources {
//            srcDir("trace-debugger/resources")
//        }
//    }
//
//    create("traceRecorderIntegrationTest") {
//        java.srcDir("trace-recorder")
//        configureClasspath()
//
//        resources {
//            srcDir("trace-recorder/resources")
//        }
//    }
//
//    dependencies {
//        // common
//        val junitVersion: String by project
//        val jctoolsVersion: String by project
//        val mockkVersion: String by project
//        val slf4jVersion: String by project
//        val gradleToolingApiVersion: String by project
//
//        val commonImplementation by configurations
//
//        commonImplementation(rootProject.sourceSets["test"].output)
//        commonImplementation("junit:junit:$junitVersion")
//        commonImplementation("org.jctools:jctools-core:$jctoolsVersion")
//        commonImplementation("io.mockk:mockk:${mockkVersion}")
//        commonImplementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
//
//        // lincheckIntegrationTest
//        val lincheckIntegrationTestImplementation by configurations
//
//        lincheckIntegrationTestImplementation(rootProject)
//        lincheckIntegrationTestImplementation("junit:junit:$junitVersion")
//        lincheckIntegrationTestImplementation("org.jctools:jctools-core:$jctoolsVersion")
//
//        // traceDebuggerIntegrationTest
//        val traceDebuggerIntegrationTestImplementation by configurations
//        val traceDebuggerIntegrationTestRuntimeOnly by configurations
//
//        traceDebuggerIntegrationTestImplementation("junit:junit:$junitVersion")
//        traceDebuggerIntegrationTestImplementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
//        traceDebuggerIntegrationTestRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")
//
//        // traceRecorderIntegrationTest
//        val traceRecorderIntegrationTestImplementation by configurations
//        val traceRecorderIntegrationTestRuntimeOnly by configurations
//
//        traceRecorderIntegrationTestImplementation("junit:junit:$junitVersion")
//        traceRecorderIntegrationTestImplementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
//        traceRecorderIntegrationTestRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")
//    }
//}

//tasks {
//    withType<JavaCompile> {
//        setupJavaToolchain(project)
//    }
//
//    withType<KotlinCompile> {
//        setupKotlinToolchain(project)
//        setupFriendPathsToRootProject()
//    }
//}

//tasks {
//    // TODO: rename to match trace-debugger/recorder gradle task naming pattern to 'lincheckIntegrationTest'
//    val lincheckIntegrationTest = register<Test>("integrationTest") {
//        configureJvmTestCommon(project)
//        group = "verification"
//
//        testClassesDirs = sourceSets["lincheckIntegrationTest"].output.classesDirs
//        classpath = sourceSets["lincheckIntegrationTest"].runtimeClasspath
//
//        enableAssertions = true
//        testLogging.showStandardStreams = true
//        outputs.upToDateWhen { false } // Always run tests when called
//    }

//    registerTraceAgentIntegrationTestsPrerequisites()
//
//    val traceDebuggerIntegrationTest = register<Test>("traceDebuggerIntegrationTest") {
//        configureJvmTestCommon(project)
//        group = "verification"
//        // TODO: do I need these explicit include's?
//        include("org/jetbrains/test/trace/debugger/*")
//
//        testClassesDirs = sourceSets["traceDebuggerIntegrationTest"].output.classesDirs
//        classpath = sourceSets["traceDebuggerIntegrationTest"].runtimeClasspath
//
//        outputs.upToDateWhen { false } // Always run tests when called
//        dependsOn(traceAgentIntegrationTestsPrerequisites)
//    }

//    val traceRecorderIntegrationTest = register<Test>("traceRecorderIntegrationTest") {
//        configureJvmTestCommon(project)
//        group = "verification"
//        include("org/jetbrains/test/trace/recorder/*")
//
//        testClassesDirs = sourceSets["traceRecorderIntegrationTest"].output.classesDirs
//        classpath = sourceSets["traceRecorderIntegrationTest"].runtimeClasspath
//
//        outputs.upToDateWhen { false } // Always run tests when called
//        dependsOn(traceAgentIntegrationTestsPrerequisites)
//    }
//}

//fun SourceSet.configureClasspath() {
//    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output + sourceSets["common"].output
//    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output + sourceSets["common"].output
//
//    // Add the root project's source sets to allow access to internal classes later via friend paths
//    configureAccessToRootProject()
//}
//
//fun SourceSet.configureAccessToRootProject() {
//    compileClasspath += rootProject.sourceSets.main.get().output + rootProject.sourceSets.test.get().output
//    runtimeClasspath += rootProject.sourceSets.main.get().output + rootProject.sourceSets.test.get().output
//}
//
//fun KotlinCompile.setupFriendPathsToRootProject() {
//    val mainSourceSet = rootProject.sourceSets.main.get().output.files
//    val testSourceSet = rootProject.sourceSets.test.get().output.files
//    val rootJarArchive = Paths.get(rootProject.buildDir.absolutePath, "libs", rootProject.name + "-" + rootProject.version + ".jar").toFile()
//    friendPaths.setFrom(friendPaths.files + mainSourceSet + testSourceSet + rootJarArchive)
//}