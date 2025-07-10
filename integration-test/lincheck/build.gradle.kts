sourceSets {
    test {
        java.srcDirs("src/test")
    }

    dependencies {
        val junitVersion: String by project
        val jctoolsVersion: String by project

        testImplementation(project(":"))
        testImplementation(rootProject.sourceSets["test"].output)
        testImplementation("junit:junit:$junitVersion")
        testImplementation("org.jctools:jctools-core:$jctoolsVersion")
    }
}

tasks {
    // TODO: rename to match trace-debugger/recorder gradle task naming pattern to 'lincheckIntegrationTest'
    val lincheckIntegrationTest = register<Test>("integrationTest") {
        configureJvmTestCommon(project)
        group = "verification"

        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath

        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called
    }
}