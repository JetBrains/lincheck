plugins {
    id("java")
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlinx.atomicfu") version "0.27.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("org.jetbrains.lincheck:lincheck:3.6")
}

tasks.test {
    useJUnitPlatform()

    outputs.upToDateWhen { false } // Always run tests
}
