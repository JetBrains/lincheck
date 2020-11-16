pluginManagement {
    val kotlin_version: String by settings
    plugins {
        java
        `maven-publish`
        kotlin("multiplatform") version kotlin_version
    }

    repositories {
        mavenCentral()
        jcenter()
        gradlePluginPortal()
    }
}

rootProject.name = "lincheck"