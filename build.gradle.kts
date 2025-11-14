plugins {
    kotlin("jvm") version "2.2.20"
}

group = "org.jetbrains.lincheck"
version = "0.1"

repositories {
    mavenCentral()
}

sourceSets {
    test {
        kotlin.srcDir("src")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.lincheck:lincheck:3.3")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

kotlin {
    jvmToolchain(21)
}