buildscript {
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:0.20.2")
    }
}
apply(plugin = "kotlinx-atomicfu")

plugins {
    java
    kotlin("jvm") version "1.8.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation(kotlin("test"))
    implementation("org.jctools:jctools-core:3.1.0")
    implementation("com.googlecode.concurrent-trees:concurrent-trees:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
    implementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:lincheck-jvm:2.16")
}

kotlin {
    // Use or download the latest jdk.
    // Remove the next line to use custom jdk version.
    jvmToolchain(19)
}

tasks {
    withType<Test> {
        // Remove these arguments for Java 8 and older versions
        jvmArgs(
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.action=ALL-UNNAMED"
        )
        exclude("**/AbstractConcurrentMapTest.class")
        exclude("**/AbstractLincheckTest.class")
        exclude("**/IntIntAbstractConcurrentMapTest.class")
        maxHeapSize = "3g"
    }
}

sourceSets.main {
    java.srcDir("src")
}

sourceSets.test {
    java.srcDir("test")
}