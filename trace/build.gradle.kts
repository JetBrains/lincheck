import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    configureKotlin()
}

java {
    configureJava()
}

sourceSets {
    main {
        java.srcDirs("src/main")
    }

    test {
        java.srcDir("src/test")

        resources {
            srcDir("src/test/resources")
        }
    }
}

tasks {
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain()
    }
    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain()
    }
}

fun JavaCompile.setupJavaToolchain() {
    val jdkToolchainVersion: String by project
    setupJavaToolchain(javaToolchains, jdkToolchainVersion)
}

fun KotlinCompile.setupKotlinToolchain() {
    val jdkToolchainVersion: String by project
    setupKotlinToolchain(javaToolchains, jdkToolchainVersion)
}

tasks.jar {
    archiveFileName.set("trace.jar")
}