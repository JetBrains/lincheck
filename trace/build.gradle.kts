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

    test {
        java.srcDir("src/test")
    }

    dependencies {
        val junitVersion: String by project

        implementation(project(":common"))

        testImplementation(project(":common"))
        testImplementation("junit:junit:${junitVersion}")
    }
}

tasks {
//    named<JavaCompile>("compileTestJava") {
//        setupJavaToolchain(project)
//    }
//    named<KotlinCompile>("compileTestKotlin") {
//        setupKotlinToolchain(project)
//    }

    withType<KotlinCompile> {
        getAccessToInternalDefinitionsOf(project(":common"))
    }
}

tasks.test {
    configureJvmTestCommon(project)
    dependsOn(":trace-recorder:traceRecorderFatJar")
    jvmArgs(
        "-javaagent:${project(":trace-recorder").buildDir}/libs/trace-recorder-fat.jar=org.jetbrains.lincheck_trace.util.TRPlayground,testing,output.txt,text,verbose"
    )
//    jvmArgs(
//        "-javaagent:${project(":trace-recorder").buildDir}/libs/trace-recorder-fat.jar=org.jetbrains.lincheck_trace.util.TRPlayground,testing,output,dump,verbose"
//    )
    jvmArgs("-Dlincheck.traceRecorderMode=true")
}

val jar = tasks.jar {
    archiveFileName.set("trace.jar")
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
            val traceArtifactId: String by project
            val traceVersion: String by project

            this.groupId = groupId
            this.artifactId = traceArtifactId
            this.version = traceVersion

            from(components["kotlin"])
            artifact(sourcesJar)
            artifact(javadocJar)

            configureMavenPublication {
                name.set(traceArtifactId)
                description.set("Lincheck trace model and (de)serialization library")
            }
        }
    }

    configureRepositories(
        artifactsRepositoryUrl = rootProject.run { uri(layout.buildDirectory.dir("artifacts/maven")) }
    )
}

configureSigning()
dependencies {
    testImplementation(kotlin("test"))
}