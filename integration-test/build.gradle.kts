import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths

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

subprojects {
    plugins.apply("java")
    plugins.apply("org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    kotlin {
        configureKotlin()
    }

    java {
        configureJava()
    }

    setupTestsJDK(project)

    tasks {
        withType<JavaCompile> {
            setupJavaToolchain(project)
        }

        withType<KotlinCompile> {
            setupKotlinToolchain(project)
            getAccessToInternalDefinitionsOf(rootProject)
        }
    }
}