import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val license_licenseName: String by project
val license_addJavaLicenseAfterPackage: String by project
val inceptionYear: String by project
val lastCopyrightYear: String by project
val kotlin_version: String by project
val kotlinx_coroutines_version: String by project
val asm_version: String by project
val atomicfu_version: String by project
val reflections_version: String by project

buildscript {
    val atomicfu_version: String by project
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicfu_version")
    }
}

apply(plugin = "kotlinx-atomicfu")

// version configured in settings.gradle.kts
plugins {
    java
    `maven-publish`
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
    jcenter()
}

kotlin {
    jvm {
        withJava()

        val main by compilations.getting {
            kotlinOptions.jvmTarget = "1.8"
        }

        val test by compilations.getting {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

    sourceSets {
        val jvmMain by getting {
            kotlin.srcDirs("src/jvm/main")
        }

        val jvmTest by getting {
            // someone should look here, and try to remove first argument
            kotlin.srcDirs("src/jvm/main", "src/jvm/test")

            dependencies {
                implementation("junit:junit:4.12")
                implementation("org.jctools:jctools-core:2.1.0")
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// https://youtrack.jetbrains.com/issue/KT-31603
sourceSets {
    main {
        java {
            srcDirs("src/jvm/main")
        }
    }

    test {
        java {
            srcDirs("src/jvm/test")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlin_version")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlin_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
    implementation("org.ow2.asm:asm-commons:$asm_version")
    implementation("org.ow2.asm:asm-util:$asm_version")
    implementation("org.reflections:reflections:$reflections_version")
}

group = "org.jetbrains.kotlinx"
version = "2.9-SNAPSHOT"
description = "Lincheck"

tasks {
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.compilerArgs.add("-XDignore.symbol.file")
    }

    withType<Test> {
        maxParallelForks = 1
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED")
    }

    withType<Jar> {
        manifest {
            attributes(
                    "Copyright" to "Copyright (C) 2015 - 2019 Devexperts, LLC\n" +
                            "                                Copyright (C) $inceptionYear - $lastCopyrightYear JetBrains, s.r.o."

            )
        }
    }

}

publishing {
    repositories {
        maven {
            url = uri("$buildDir/repository")
        }
    }
}
