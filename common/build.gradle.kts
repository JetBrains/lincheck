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

        compileOnly(project(":bootstrap"))
        api("org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-stdlib-common:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
        api("org.ow2.asm:asm-commons:${asmVersion}")
    }
}

tasks {
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain(project)
    }
    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain(project)
    }
}


val jar = tasks.jar {
    archiveFileName.set("common.jar")
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
            val commonArtifactId: String by project
            val commonVersion: String by project

            this.groupId = groupId
            this.artifactId = commonArtifactId
            this.version = commonVersion

            from(components["kotlin"])
            artifact(sourcesJar)
            artifact(javadocJar)

            configureMavenPublication {
                name.set(commonArtifactId)
                description.set("Lincheck common components library")
            }
        }
    }

    configureRepositories(
        artifactsRepositoryUrl = rootProject.run { uri(layout.buildDirectory.dir("artifacts/maven")) }
    )
}