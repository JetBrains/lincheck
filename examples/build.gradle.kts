plugins {
    kotlin("jvm") version "2.3.21"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.lincheck:lincheck:3.6")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}