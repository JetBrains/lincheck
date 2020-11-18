pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        java
        kotlin("multiplatform") version kotlinVersion
    }

    repositories {
        maven(url = "https://dl.bintray.com/kotlin/kotlinx")
        mavenCentral()
        jcenter()
        gradlePluginPortal()
    }
}

val name: String by settings
rootProject.name = name
enableFeaturePreview("GRADLE_METADATA")