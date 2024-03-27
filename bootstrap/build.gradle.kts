import org.jetbrains.kotlin.gradle.tasks.*

plugins {
    java
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

sourceSets.main {
    java.srcDirs("src")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

tasks.jar {
    archiveFileName.set("bootstrap.jar")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
