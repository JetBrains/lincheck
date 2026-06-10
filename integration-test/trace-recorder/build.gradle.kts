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
    val integrationTestSuiteType: TraceAgentIntegrationTestSuite? = when (integrationTestSuite?.lowercase()) {
        "basic" -> TraceAgentIntegrationTestSuite.Basic
        "kotlincompiler" -> TraceAgentIntegrationTestSuite.KotlinCompiler
        "ktor" -> TraceAgentIntegrationTestSuite.Ktor
        "ij" -> TraceAgentIntegrationTestSuite.IJ
        "all", null -> TraceAgentIntegrationTestSuite.All
        else -> null
    }

    // Optional deterministic sharding, e.g. `-PintegrationTestShard=2/4`,
    // used to parallelize the (large) suite across separate CI build configurations.
    // Sharding is done here, by us, rather than via TeamCity's statistics-based "parallel tests" feature,
    // which cannot split a suite whose tests are all JUnit `@Nested` classes with a uniform `test()` method.
    val integrationTestShard: String? by project

    register<Test>("traceRecorderIntegrationTest") {
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
            // Unrecognized suite (e.g. a value meant for another integration-test module): run nothing.
            null -> {
                exclude("**/*")
                doFirst {
                    logger.warn("Unrecognized integration test suite '$integrationTestSuite'; running no trace-recorder integration tests")
                }
            }
        }

        // Assign each top-level generated test class to exactly one shard via a stable hash of its
        // name. Because the mapping is total and deterministic, the shards form a disjoint, complete
        // partition of the suite — every test runs in exactly one shard and none are silently dropped.
        if (integrationTestShard != null) {
            require(integrationTestSuiteType == TraceAgentIntegrationTestSuite.Ktor) {
                "-PintegrationTestShard is only supported together with -PintegrationTestSuite=ktor"
            }
            val parts = integrationTestShard!!.split("/")
            require(parts.size == 2) {
                "Invalid -PintegrationTestShard '$integrationTestShard', expected '<index>/<total>' (1-based), e.g. '2/4'"
            }
            val shardIndex = parts[0].toInt() // 1-based
            val totalShards = parts[1].toInt()
            require(totalShards >= 1 && shardIndex in 1..totalShards) {
                "Invalid -PintegrationTestShard '$integrationTestShard': index must be in 1..total"
            }
            logger.lifecycle("Running ktor integration tests shard $shardIndex of $totalShards")
            exclude { element ->
                val fileName = element.name
                if (!fileName.endsWith(".class")) return@exclude false
                // Group by the top-level class (drop any nested `$...` and the `.class` suffix) so a
                // class and all of its `@Nested` children always land in the same shard.
                val topLevelClass = fileName.removeSuffix(".class").substringBefore('$')
                Math.floorMod(topLevelClass.hashCode(), totalShards) != shardIndex - 1
            }
        }

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
        dependsOn(copyTraceRecorderFatJar)
    }

    // Regenerates the `…/impl/generated/*GeneratedTests.kt` files from the `*Tests.json` data.
    // The generated sources are committed and guarded by `TestGeneratedDataCorrectness`.
    register<JavaExec>("regenerateIntegrationTests") {
        group = "build"
        description = "Regenerates the committed generated trace-recorder integration test classes."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("org.jetbrains.trace.recorder.test.runner.GenerateTestsKt")
        workingDir = projectDir
        javaLauncher.set(
            project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
                val jdkToolchainVersion: String by project
                languageVersion.set(JavaLanguageVersion.of(jdkToolchainVersion.toInt()))
            }
        )
    }
}

