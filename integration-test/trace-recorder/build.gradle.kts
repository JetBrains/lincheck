plugins {
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
}

sourceSets {
    main {
        java.srcDirs("src/main")

        resources {
            srcDir("src/main/resources")
        }
    }

    dependencies {
        val slf4jVersion: String by project
        val kotlinxSerializationVersion: String by project

        implementation(kotlin("reflect"))
        implementation(project(":integration-test:common"))
        implementation("org.slf4j:slf4j-simple:$slf4jVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    }
}

enum class TraceAgentIntegrationTestSuite {
    Basic, KotlinCompiler, Ktor, IJ, All
}

tasks {
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    registerTraceAgentIntegrationTestsPrerequisites()

    val copyTraceRecorderFatJar = copyTraceAgentFatJar(project(":trace-recorder"), "trace-recorder-fat.jar")

    val integrationTestSuite: String? by project
    val integrationTestSuiteType = when (integrationTestSuite?.lowercase()) {
        "basic" -> TraceAgentIntegrationTestSuite.Basic
        "kotlincompiler" -> TraceAgentIntegrationTestSuite.KotlinCompiler
        "ktor" -> TraceAgentIntegrationTestSuite.Ktor
        "ij" -> TraceAgentIntegrationTestSuite.IJ
        "all", null -> TraceAgentIntegrationTestSuite.All
        else -> error("Unknown integration test suite type: $integrationTestSuite")
    }

    val traceRecorderIntegrationTest = register<Test>("traceRecorderIntegrationTest") {
        useJUnitPlatform()
        configureJvmTestCommon(project)
        group = "verification"

        // We cannot put integration tests in the 'test' source set because they will
        // be run together with unit tests on the ':test' task, which we don't want.
        // Thus, all integration tests use 'main' source set instead and manually
        // set up test classes location.
        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        // Do not run extended tests in the basic task
        when (integrationTestSuiteType) {
            TraceAgentIntegrationTestSuite.KotlinCompiler -> include("**/*KotlinCompilerTraceRecorderJsonIntegrationTests*")
            TraceAgentIntegrationTestSuite.Ktor -> include("**/*KtorTraceRecorderJsonIntegrationTests*")
            TraceAgentIntegrationTestSuite.IJ -> include("**/*IJTraceRecorderJsonIntegrationTests*")
            TraceAgentIntegrationTestSuite.All -> {}
            TraceAgentIntegrationTestSuite.Basic -> exclude(
                "**/*KotlinCompilerTraceRecorderJsonIntegrationTests*",
                "**/*KtorTraceRecorderJsonIntegrationTests*",
                "**/*IJTraceRecorderJsonIntegrationTests*",
            )
        }

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
        dependsOn(copyTraceRecorderFatJar)
    }
}
