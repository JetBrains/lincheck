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

    implementation(files("/home/maxim/Documents/pcj/target/classes"))
    implementation(files("/home/maxim/Documents/pcj/target/test_classes"))

    val junitVersion: String by project
    testImplementation("junit:junit:$junitVersion")
}

tasks {
    withType<Test> {
        systemProperty("java.library.path", "/home/maxim/Documents/pcj/target/cppbuild")
    }
}
