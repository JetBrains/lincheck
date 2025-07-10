repositories {
    mavenCentral()
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
}

sourceSets {
    test {
        java.srcDirs("src/test")

        resources {
            srcDir("src/test/resources")
        }
    }

    dependencies {
        val slf4jVersion: String by project

        testImplementation(project(":integration-test:common"))
        testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
    }
}

tasks {
    processTestResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    registerTraceAgentIntegrationTestsPrerequisites()

    val traceRecorderIntegrationTest = register<Test>("traceRecorderIntegrationTest") {
        configureJvmTestCommon(project)
        group = "verification"
        include("org/jetbrains/test/trace/recorder/*")

        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath

        outputs.upToDateWhen { false } // Always run tests when called
        dependsOn(traceAgentIntegrationTestsPrerequisites)
    }
}