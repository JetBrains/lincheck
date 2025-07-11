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

    tasks.withType<Test> {
        javaLauncher.set(
            javaToolchains.launcherFor {
                val jdkToolchainVersion: String by project
                val testInTraceDebuggerMode: String by project
                val jdkVersion = jdkToolchainVersion.toInt()
                // https://github.com/JetBrains/lincheck/issues/500
                val jreVersion = if (testInTraceDebuggerMode.toBoolean() && jdkVersion == 8) 17 else jdkVersion
                languageVersion.set(JavaLanguageVersion.of(jreVersion))
            }
        )
    }

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