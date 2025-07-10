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

        implementation(project(":integration-test:common"))
        implementation("org.slf4j:slf4j-simple:$slf4jVersion")
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

        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
        dependsOn(copyTraceRecorderFatJar)
    }
}