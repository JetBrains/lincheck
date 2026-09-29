import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaLanguageVersion

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
    Basic,
    KotlinxImmutableCollections,
    KotlinxImmutableCollectionsMultipleBreakpointsOnSameLine,
    Ktor,
    KotlinCompiler,
    All,
}

tasks {
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    val integrationTestSuite: String? by project
    val integrationTestSuiteType: LiveDebuggerIntegrationTestSuite? = when (integrationTestSuite?.lowercase()) {
        "basic" -> LiveDebuggerIntegrationTestSuite.Basic
        "ktor" -> LiveDebuggerIntegrationTestSuite.Ktor
        "kotlinximmutablecollections" -> LiveDebuggerIntegrationTestSuite.KotlinxImmutableCollections
        "kotlinximmutablecollectionsmultiplebreakpointsonsameline" ->
            LiveDebuggerIntegrationTestSuite.KotlinxImmutableCollectionsMultipleBreakpointsOnSameLine
        "kotlincompiler" -> LiveDebuggerIntegrationTestSuite.KotlinCompiler
        "all", null -> LiveDebuggerIntegrationTestSuite.All
        else -> null
    }

    val prerequisites = registerTraceAgentIntegrationTestsPrerequisites(when (integrationTestSuiteType) {
        LiveDebuggerIntegrationTestSuite.Basic, null -> emptySet()
        LiveDebuggerIntegrationTestSuite.KotlinxImmutableCollections,
        LiveDebuggerIntegrationTestSuite.KotlinxImmutableCollectionsMultipleBreakpointsOnSameLine ->
            setOf("kotlinx.collections.immutable")
        LiveDebuggerIntegrationTestSuite.Ktor -> setOf("ktor")
        LiveDebuggerIntegrationTestSuite.KotlinCompiler -> setOf("kotlin")
        LiveDebuggerIntegrationTestSuite.All -> setOf("kotlinx.collections.immutable", "ktor", "kotlin")
    })
    val copyClasspathClashProjects = when (integrationTestSuiteType) {
        LiveDebuggerIntegrationTestSuite.Basic, LiveDebuggerIntegrationTestSuite.All -> copyClasspathClashTestProjects()
        else -> emptyList()
    }
    val copyLiveDebuggerFatJar = copyTraceAgentFatJar(project(":live-debugger"), "app-glass-agent.jar", prerequisites)
    val copyAgentExpressionTestProject = register<Copy>("copyAgentExpressionTestProject") {
        from(rootProject.layout.projectDirectory.dir("integration-test/test-projects/agent-expressions"))
        into(layout.buildDirectory.dir("integrationTestProjects/agent-expressions"))
    }

    register<Test>("liveDebuggerIntegrationTest") {
        useJUnitPlatform()
        configureJvmTestCommon(project)
        group = "verification"

        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        when (integrationTestSuiteType) {
            LiveDebuggerIntegrationTestSuite.Ktor -> include("**/*KtorLiveDebuggerJsonIntegrationTests*")
            LiveDebuggerIntegrationTestSuite.KotlinxImmutableCollections -> include("**/*KotlinxImmutableCollectionsLiveDebuggerJsonIntegrationTests*")
            LiveDebuggerIntegrationTestSuite.KotlinxImmutableCollectionsMultipleBreakpointsOnSameLine ->
                include("**/*KotlinxImmutableCollectionsMultipleBreakpointsOnSameLineLiveDebuggerJsonIntegrationTests*")
            LiveDebuggerIntegrationTestSuite.KotlinCompiler -> include("**/*KotlinCompilerLiveDebuggerJsonIntegrationTests*")
            LiveDebuggerIntegrationTestSuite.All -> {}
            // Everything that is not one of the (heavy) external-project suites above.
            LiveDebuggerIntegrationTestSuite.Basic -> exclude(
                "**/*KtorLiveDebuggerJsonIntegrationTests*",
                "**/*KotlinxImmutableCollectionsLiveDebuggerJsonIntegrationTests*",
                "**/*KotlinxImmutableCollectionsMultipleBreakpointsOnSameLineLiveDebuggerJsonIntegrationTests*",
                "**/*KotlinCompilerLiveDebuggerJsonIntegrationTests*",
            )
            // Unrecognized suite (e.g. a value meant for another integration-test module): run nothing.
            null -> {
                exclude("**/*")
                doFirst {
                    logger.warn("Unrecognized integration test suite '$integrationTestSuite'; running no live-debugger integration tests")
                }
            }
        }

        outputs.upToDateWhen { false } // Always run tests when called
        outputs.cacheIf { false }
        dependsOn(prerequisites)
        dependsOn(copyLiveDebuggerFatJar)
        copyClasspathClashProjects.forEach { dependsOn(it) }
        if (integrationTestSuiteType == LiveDebuggerIntegrationTestSuite.Basic ||
            integrationTestSuiteType == LiveDebuggerIntegrationTestSuite.All) {
            dependsOn(copyAgentExpressionTestProject)
        }
    }

    // Regenerates the `…/impl/generated/*GeneratedTests.kt` files from the `*Tests.json` data.
    // The generated sources are committed and guarded by `LiveDebuggerTestGeneratedDataCorrectness`.
    register<JavaExec>("regenerateIntegrationTests") {
        group = "build"
        description = "Regenerates the committed generated live-debugger integration test classes."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("org.jetbrains.live.debugger.test.runner.GenerateTestsKt")
        workingDir = projectDir
        javaLauncher.set(
            project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
                val jdkToolchainVersion: String by project
                languageVersion.set(JavaLanguageVersion.of(jdkToolchainVersion.toInt()))
            }
        )
    }
}
