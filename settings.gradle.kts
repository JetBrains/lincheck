pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        java
        kotlin("multiplatform") version kotlinVersion
    }

    repositories {
        maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlinx/maven")
        mavenCentral()
        jcenter()
        gradlePluginPortal()
    }
}

val name: String by settings
rootProject.name = name