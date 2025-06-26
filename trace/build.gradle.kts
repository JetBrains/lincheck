import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm")
    id("maven-publish")
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

val jar = tasks.jar {
    archiveFileName.set("trace.jar")
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    from(sourceSets["main"].allSource)
    archiveClassifier.set("sources")
}

publishing {
    publications {
        register("maven", MavenPublication::class) {
            val artifactId: String by project
            val groupId: String by project
            val version: String by project

            this.artifactId = artifactId
            this.groupId = groupId
            this.version = version

            from(components["kotlin"])
            artifact(sourcesJar)
            // artifact(javadocJar)

            configureMavenPublication {
                name.set(artifactId)
                description.set("Lincheck trace model and (de)serialization library")
            }
        }
    }

    configureRepositories(
        artifactsRepositoryUrl = rootProject.run { uri(layout.buildDirectory.dir("artifacts/maven")) }
    )
}