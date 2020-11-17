import org.gradle.jvm.tasks.Jar

// atomicfu
buildscript {
    val atomicfuVersion: String by project
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicfuVersion")
    }
}
apply(plugin = "kotlinx-atomicfu")

plugins {
    java
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

            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib:${project.property("kotlinVersion")}")
                api("org.jetbrains.kotlin:kotlin-stdlib-common:${project.property("kotlinVersion")}")
                api("org.jetbrains.kotlin:kotlin-reflect:${project.property("kotlinVersion")}")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("kotlinxCoroutinesVersion")}")
                api("org.ow2.asm:asm-commons:${project.property("asmVersion")}")
                api("org.ow2.asm:asm-util:${project.property("asmVersion")}")
                api("org.reflections:reflections:${project.property("reflectionsVersion")}")
            }
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

sourceSets.main {
    java.srcDirs("src/jvm/main")
}

sourceSets.test {
    java.srcDirs("src/jvm/test")
}

tasks {
    withType<Test> {
        maxParallelForks = 1
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED")
    }

    withType<Jar> {
        manifest {
            val inceptionYear: String by project
            val lastCopyrightYear: String by project
            attributes("Copyright" to
                "Copyright (C) 2015 - 2019 Devexperts, LLC\n                                " +
                "Copyright (C) $inceptionYear - $lastCopyrightYear JetBrains, s.r.o."
            )
        }
    }
}
