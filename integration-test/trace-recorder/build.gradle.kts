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

tasks {
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    registerTraceAgentIntegrationTestsPrerequisites()

    val copyTraceRecorderFatJar = copyTraceAgentFatJar(project(":trace-recorder"), "trace-recorder-fat.jar")

    val traceRecorderIntegrationTest = register<Test>("traceRecorderIntegrationTest") {
        configureJvmTestCommon(project)
        group = "verification"

        // We cannot put integration tests in the 'test' source set because they will
        // be run together with unit tests on the ':test' task, which we don't want.
        // Thus, all integration tests use 'main' source set instead and manually
        // set up test classes location.
        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        // Do not run extended tests in the basic task
        useJUnit {
            excludeCategories("org.jetbrains.trace.recorder.test.runner.KotlinCompilerTraceRecorderTest")
            excludeCategories("org.jetbrains.trace.recorder.test.runner.KtorTraceRecorderTest")
        }

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
        dependsOn(copyTraceRecorderFatJar)
    }

    val traceRecorderIntegrationTestKotlinCompiler = register<Test>("traceRecorderIntegrationTestKotlinCompiler") {
        configureJvmTestCommon(project)
        group = "verification"

        // Use the same source set as the basic integration tests
        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        // Only run tests marked as extended
        useJUnit {
            includeCategories("org.jetbrains.trace.recorder.test.runner.KotlinCompilerTraceRecorderTest")
        }

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
        dependsOn(copyTraceRecorderFatJar)
    }

    val traceRecorderIntegrationTestKtor = register<Test>("traceRecorderIntegrationTestKtor") {
        configureJvmTestCommon(project)
        group = "verification"

        // Use the same source set as the basic integration tests
        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        // Only run tests marked as extended
        useJUnit {
            includeCategories("org.jetbrains.trace.recorder.test.runner.KtorTraceRecorderTest")
        }

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
        dependsOn(copyTraceRecorderFatJar)
    }
    
    val traceRecorderIntegrationTestAll = register<Test>("traceRecorderIntegrationTestAll") {
        group = "verification"

        finalizedBy(traceRecorderIntegrationTest)
        finalizedBy(traceRecorderIntegrationTestKtor)
        finalizedBy(traceRecorderIntegrationTestKotlinCompiler)

        traceRecorderIntegrationTestKotlinCompiler.get().mustRunAfter(traceRecorderIntegrationTestKtor)
        traceRecorderIntegrationTestKtor.get().mustRunAfter(traceRecorderIntegrationTest)
    }
}
