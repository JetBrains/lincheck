import java.time.Duration

plugins {
    kotlin("jvm") version "2.3.21"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.lincheck:lincheck")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

sourceSets.test {
    kotlin.srcDir("src/verification/kotlin")
    resources.srcDir("src/verification/resources")
}

tasks.register<Test>("verifyExamples") {
    description = "Runs documentation examples and checks their expected successes and failures."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    systemProperty("lincheck.defaultRandomScenarios", "10")
    systemProperty("lincheck.defaultInvocationsPerScenario", "1000")
    systemProperty("lincheck.defaultInvocations", "1000")
    maxParallelForks = 4
    forkEvery = 1
    maxHeapSize = "3g"
    testLogging.events("passed", "failed", "skipped")
    timeout.set(Duration.ofMinutes(60))
    // Stress examples must execute even when their compiled inputs have not changed.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
