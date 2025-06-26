pluginManagement {
    val kotlinVersion: String by settings
    val dokkaVersion: String by settings
    val atomicfuVersion: String by settings

    plugins {
        java
        kotlin("jvm") version kotlinVersion
        id("org.jetbrains.dokka") version dokkaVersion
        id("org.jetbrains.kotlinx.atomicfu") version atomicfuVersion
    }

    repositories {
        maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlinx/maven")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.7.0")
}

val projectName: String by settings
rootProject.name = projectName

include(":bootstrap")
include(":jvm-agent")
include(":common")
include(":trace")
include(":trace-recorder")
include(":trace-debugger")