import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    id("maven-publish")
    id("org.jetbrains.dokka")
}

group = property("groupId") as String
version = rootProject.version

repositories {
    mavenCentral()
}

sourceSets {
    main { java.srcDirs("src/main") }
    test { java.srcDirs("src/test") }
}

dependencies {
    api(project(":common"))
    api(project(":trace"))
    val javaWebSocketVersion: String by project
    api("org.java-websocket:Java-WebSocket:$javaWebSocketVersion")
    val junitVersion: String by project
    testImplementation("junit:junit:$junitVersion")
}

setupTestsJDK(project)
tasks.named<JavaCompile>("compileTestJava") { setupJavaToolchain(project) }
tasks.named<KotlinCompile>("compileTestKotlin") { setupKotlinToolchain(project) }
tasks.test { configureJvmTestCommon(project) }

tasks.jar { archiveFileName.set("live-debugger-protocol.jar") }
java { withSourcesJar() }
val javadocJar = createJavadocJar()

publishing {
    publications {
        register<MavenPublication>("maven") {
            artifactId = "lincheck-live-debugger-protocol"
            from(components["java"])
            artifact(javadocJar)
            configureMavenPublication {
                name.set(artifactId)
                description.set("Live debugger wire protocol and WebSocket transport")
            }
        }
    }
    configureRepositories(
        artifactsRepositoryUrl = rootProject.run { uri(layout.buildDirectory.dir("artifacts/maven")) }
    )
}

configureSigning()
