import groovy.util.*
import kotlinx.team.infra.*
import org.gradle.jvm.tasks.Jar

// atomicfu
buildscript {
    val atomicfuVersion: String by project
    val serializationPluginVersion: String by project
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicfuVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$serializationPluginVersion")
    }
}
apply(plugin = "kotlinx-atomicfu")
apply(plugin = "kotlinx-serialization")

plugins {
    java
    kotlin("multiplatform")
    id("maven-publish")
    id("kotlinx.team.infra") version "0.3.0-dev-64"
}

repositories {
    mavenCentral()
    jcenter()
}

kotlin {
    // we have to create custom sourceSets in advance before defining corresponding compilation targets
    sourceSets.create("jvmBenchmark")

    jvm {
        withJava()

        val main by compilations.getting {
            kotlinOptions.jvmTarget = "11"
        }

        val test by compilations.getting {
            kotlinOptions.jvmTarget = "11"
        }

        val benchmark by compilations.creating {
            kotlinOptions.jvmTarget = "11"

            defaultSourceSet {
                dependencies {
                    implementation(main.compileDependencyFiles + main.output.classesDirs)
                }
            }

            val benchmarksClassPath = compileDependencyFiles + runtimeDependencyFiles + output.allOutputs
            val benchmarksTestClassesDirs = output.classesDirs

            // task allowing to run benchmarks using JUnit API
            val benchmark = tasks.register<Test>("jvmBenchmark") {
                classpath = benchmarksClassPath
                testClassesDirs = benchmarksTestClassesDirs
            }

            // task aggregating all benchmarks into single suite and producing custom reports
            val benchmarkSuite = tasks.register<Test>("jvmBenchmarkSuite") {
                classpath = benchmarksClassPath
                testClassesDirs = benchmarksTestClassesDirs
                filter {
                    includeTestsMatching("LincheckBenchmarksSuite")
                }
                // pass the properties
                systemProperty("statisticsGranularity", System.getProperty("statisticsGranularity"))
                // always re-run test suite
                outputs.upToDateWhen { false }
            }

            // task producing plots given the benchmarks report file
            val benchmarkPlots by tasks.register<JavaExec>("runBenchmarkPlots") {
                classpath = benchmarksClassPath
                mainClass.set("org.jetbrains.kotlinx.lincheck_benchmark.PlotsKt")
            }
        }
    }

    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir("src/jvm/main")

            val kotlinVersion: String by project
            val kotlinxCoroutinesVersion: String by project
            val asmVersion: String by project
            val reflectionsVersion: String by project
            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
                api("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion")
                api("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
                api("org.ow2.asm:asm-commons:$asmVersion")
                api("org.ow2.asm:asm-util:$asmVersion")
                api("org.reflections:reflections:$reflectionsVersion")
            }
        }

        val jvmTest by getting {
            kotlin.srcDir("src/jvm/test")

            val junitVersion: String by project
            val jctoolsVersion: String by project
            val mockkVersion: String by project
            dependencies {
                implementation("junit:junit:$junitVersion")
                implementation("org.jctools:jctools-core:$jctoolsVersion")
                implementation("io.mockk:mockk:${mockkVersion}")
            }
        }

        val jvmBenchmark by getting {
            kotlin.srcDirs("src/jvm/benchmark")

            val junitVersion: String by project
            val jctoolsVersion: String by project
            val serializationVersion: String by project
            val letsPlotVersion: String by project
            val letsPlotKotlinVersion: String by project
            dependencies {
                implementation("junit:junit:$junitVersion")
                implementation("org.jctools:jctools-core:$jctoolsVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("org.jetbrains.lets-plot:lets-plot-common:$letsPlotVersion")
                implementation("org.jetbrains.lets-plot:lets-plot-kotlin-jvm:$letsPlotKotlinVersion")
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

sourceSets.main {
    java.srcDirs("src/jvm/main")
}

sourceSets.test {
    java.srcDirs("src/jvm/test")
    resources {
        srcDir("src/jvm/test/resources")
    }
}

tasks {
    replace("jvmSourcesJar", Jar::class).run {
        from(sourceSets["main"].allSource)
    }
    withType<Test> {
        maxParallelForks = 1
        jvmArgs(
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.action=ALL-UNNAMED"
        )
    }

    withType<Jar> {
        manifest {
            val inceptionYear: String by project
            val lastCopyrightYear: String by project
            attributes(
                "Copyright" to
                        "Copyright (C) 2015 - 2019 Devexperts, LLC\n                                " +
                        "Copyright (C) $inceptionYear - $lastCopyrightYear JetBrains, s.r.o."
            )
        }
    }
}

infra {
    teamcity {
        val name: String by project
        val version: String by project
        libraryStagingRepoDescription = "$name $version"
    }
    publishing {
        include(":")

        libraryRepoUrl = "https://github.com/Kotlin/kotlinx-lincheck"
        sonatype {}
    }
}

mavenPublicationsPom {
    description.set("Lincheck - Framework for testing concurrent data structures")
    val licenceName = "Mozilla Public License Version 2.0"
    licenses {
        license {
            name.set(licenceName)
            url.set("https://www.mozilla.org/en-US/MPL/2.0/")
            distribution.set("repo")
        }
    }
    withXml {
        removeAllLicencesExceptOne(licenceName)
    }
}

// kotlinx.team.infra adds Apache License, Version 2.0, remove it manually
fun XmlProvider.removeAllLicencesExceptOne(licenceName: String) {
    val licenseList = (asNode()["licenses"] as NodeList)[0] as Node
    val licenses = licenseList["license"] as NodeList
    licenses.filterIsInstance<Node>().forEach { licence ->
        val name = (licence["name"] as NodeList)[0] as Node
        val nameValue = (name.value() as NodeList)[0] as String
        if (nameValue != licenceName) {
            licenseList.remove(licence)
        }
    }
}
