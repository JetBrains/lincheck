plugins {
    java
    kotlin("jvm")
    id("kotlinx.team.infra") version "0.4.0-dev-80"
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

    kotlin {
        configureKotlin()
    }

    java {
        configureJava()
    }
}

val bootstrapJar = tasks.register<Copy>("bootstrapJar") {
    dependsOn(":bootstrap:jar")
    from(file("${project(":bootstrap").layout.buildDirectory.get()}/libs/bootstrap.jar"))
    into(file("${project(":common").layout.buildDirectory.get()}/resources/main"))
}