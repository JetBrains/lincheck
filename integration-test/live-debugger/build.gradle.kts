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

        implementation(kotlin("reflect"))
        implementation(project(":integration-test:common"))
        implementation("org.slf4j:slf4j-simple:$slf4jVersion")
    }
}

enum class LiveDebuggerIntegrationTestSuite {
    Ktor, KotlinxImmutableCollections, KotlinCompiler, All
}

tasks {
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    registerTraceAgentIntegrationTestsPrerequisites()

    val copyLiveDebuggerFatJar = copyTraceAgentFatJar(project(":live-debugger"), "live-debugger-fat.jar")

    val liveDebuggerSuite: String? by project
    val integrationTestSuiteType = when (liveDebuggerSuite?.lowercase()) {
        "ktor" -> LiveDebuggerIntegrationTestSuite.Ktor
        "kotlinximmutablecollections" -> LiveDebuggerIntegrationTestSuite.KotlinxImmutableCollections
        "kotlincompiler" -> LiveDebuggerIntegrationTestSuite.KotlinCompiler
        "all", null -> LiveDebuggerIntegrationTestSuite.All
        else -> error("Unknown live-debugger suite: $liveDebuggerSuite")
    }

    register<Test>("liveDebuggerIntegrationTest") {
        useJUnitPlatform()
        configureJvmTestCommon(project)
        group = "verification"

        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        when (integrationTestSuiteType) {
            LiveDebuggerIntegrationTestSuite.Ktor -> include("**/*KtorLiveDebuggerTraceRecorderJsonIntegrationTests*")
            LiveDebuggerIntegrationTestSuite.KotlinxImmutableCollections -> include("**/*KotlinxImmutableCollectionsLiveDebuggerTraceRecorderJsonIntegrationTests*")
            LiveDebuggerIntegrationTestSuite.KotlinCompiler -> include("**/*KotlinCompilerLiveDebuggerTraceRecorderJsonIntegrationTests*")
            LiveDebuggerIntegrationTestSuite.All -> {}
        }

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
        dependsOn(copyLiveDebuggerFatJar)
    }
}
