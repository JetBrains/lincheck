sourceSets {
    main {
        java.srcDirs("src/main")
    }

    dependencies {
        val junitVersion: String by project
        val jctoolsVersion: String by project

        implementation(project(":"))
        // Lincheck integration tests depend on `AbstractLincheckTest`
        // which is defined in root project's 'test' source set
        implementation(rootProject.sourceSets["test"].output)
        implementation("junit:junit:$junitVersion")
        implementation("org.jctools:jctools-core:$jctoolsVersion")
    }
}

tasks {
    // TODO: rename to match trace-debugger/recorder gradle task naming pattern to 'lincheckIntegrationTest'
    val lincheckIntegrationTest = register<Test>("integrationTest") {
        configureJvmTestCommon(project)
        group = "verification"

        // We cannot put integration tests in the 'test' source set because they will
        // be run together with unit tests on the ':test' task, which we don't want.
        // Thus, all integration tests use 'main' source set instead and manually
        // set up test classes location.
        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called
    }
}