repositories {
    mavenCentral()
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
}

sourceSets {
    main {
        java.srcDirs("src/main")
    }

    dependencies {
        val junitVersion: String by project
        val jctoolsVersion: String by project
        val mockkVersion: String by project
        val gradleToolingApiVersion: String by project

        api("junit:junit:$junitVersion")
        api("org.jctools:jctools-core:$jctoolsVersion")
        api("io.mockk:mockk:${mockkVersion}")
        implementation(rootProject.sourceSets["test"].output)
        implementation("org.gradle:gradle-tooling-api:${gradleToolingApiVersion}")
    }
}
