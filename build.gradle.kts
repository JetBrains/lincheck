import org.gradle.jvm.tasks.Jar

// atomicfu
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:${project.property("atomicfuVersion")}")
    }
}

apply(plugin = "kotlinx-atomicfu")

// versions configured in settings.gradle.kts
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
sourceSets.main {
    java.srcDirs("src/jvm/main")
}

sourceSets.test {
    java.srcDirs("src/jvm/test")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${project.property("kotlinVersion")}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-common:${project.property("kotlinVersion")}")
    implementation("org.jetbrains.kotlin:kotlin-reflect:${project.property("kotlinVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("kotlinxCoroutinesVersion")}")
    implementation("org.ow2.asm:asm-commons:${project.property("asmVersion")}")
    implementation("org.ow2.asm:asm-util:${project.property("asmVersion")}")
    implementation("org.reflections:reflections:${project.property("reflectionsVersion")}")
}

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
            val inceptionYear: String by project
            val lastCopyrightYear: String by project

            attributes(
                    "Copyright" to "Copyright (C) 2015 - 2019 Devexperts, LLC\n" +
                            "                                Copyright (C) $inceptionYear - $lastCopyrightYear JetBrains, s.r.o."

            )
        }
    }
}

group = project.property("group")!!
version = project.property("version")!!
