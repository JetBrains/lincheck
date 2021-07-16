plugins {
    kotlin("jvm")
    java
}

group = "org.jetbrains.kotlinx"
version = "2.15-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":"))
    implementation(files("/Users/Maksim.Zuev/Documents/pcj/target/classes"))
}
