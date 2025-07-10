sourceSets {
    main {
        java.srcDirs("src/main")
    }

    dependencies {
        val junitVersion: String by project
        val jctoolsVersion: String by project

        implementation(project(":"))
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

        testClassesDirs = sourceSets["main"].output.classesDirs
        classpath = sourceSets["main"].runtimeClasspath

        enableAssertions = true
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false } // Always run tests when called
    }
}