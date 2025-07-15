import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("maven-publish")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java.srcDirs("src/main")
    }

    dependencies {
        // main
        val kotlinVersion: String by project
        val asmVersion: String by project
        val byteBuddyVersion: String by project

        compileOnly(project(":bootstrap"))
        implementation(project(":common"))

        api("org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-stdlib-common:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
        api("org.ow2.asm:asm-commons:${asmVersion}")
        api("org.ow2.asm:asm-util:${asmVersion}")
        api("net.bytebuddy:byte-buddy:${byteBuddyVersion}")
        api("net.bytebuddy:byte-buddy-agent:${byteBuddyVersion}")
    }
}

tasks {
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain(project)
    }
    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain(project)
    }

    withType<KotlinCompile> {
        getAccessToInternalDefinitionsOf(project(":common"))
    }

    withType<Jar> {
        dependsOn(":bootstrapJar")
    }
}

val jar = tasks.jar {
    archiveFileName.set("jvm-agent.jar")
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    from(sourceSets["main"].allSource)
    archiveClassifier.set("sources")
}

val javadocJar = createJavadocJar()

publishing {
    publications {
        register("maven", MavenPublication::class) {
            val groupId: String by project
            val jvmAgentArtifactId: String by project
            val jvmAgentVersion: String by project

            this.groupId = groupId
            this.artifactId = jvmAgentArtifactId
            this.version = jvmAgentVersion

            from(components["kotlin"])
            artifact(sourcesJar)
            artifact(javadocJar)

            configureMavenPublication {
                name.set(jvmAgentArtifactId)
                description.set("Lincheck JVM agent instrumentation library")
            }
        }
    }

    configureRepositories(
        artifactsRepositoryUrl = rootProject.run { uri(layout.buildDirectory.dir("artifacts/maven")) }
    )
}

configureSigning()