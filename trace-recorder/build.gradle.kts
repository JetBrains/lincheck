import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("maven-publish")
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

sourceSets {
    main {
        java.srcDirs("src/main")
    }

    dependencies {
        // main
        val asmVersion: String by project

        compileOnly(project(":bootstrap"))
        implementation(project(":common"))
        implementation(project(":jvm-agent"))
        implementation(project(":trace"))
        implementation(project(":tracing-agent"))

        api(kotlin("reflect"))
        api("org.ow2.asm:asm-commons:${asmVersion}")
        api("org.ow2.asm:asm-util:${asmVersion}")
    }
}

// byte-buddy is only used by `ByteBuddyAgent.install()` in `LincheckInstrumentation`,
// the dynamic self-attach path taken when no `Instrumentation` instance is supplied --
// unreachable here, since a `-javaagent` always receives one from `premain`.
// It still arrives transitively as an `api` dependency of `:jvm-agent`,
// and `runtimeClasspath` is what gets packed into `agent-payload.jar`, so exclude it explicitly.
configurations.runtimeClasspath {
    exclude(group = "net.bytebuddy")
}

tasks {
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain(project)
    }
    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain(project)
    }

    withType<KotlinCompile> {
        getAccessToInternalDefinitionsOf(
            project(":common"),
            project(":trace"),
            project(":tracing-agent")
        )
    }
}

registerTraceAgentTasks(
    fatJarName = "trace-recorder-fat",
    fatJarTaskName = "traceRecorderFatJar",
    premainClass = "org.jetbrains.lincheck.trace.recorder.TraceRecorderAgent"
)

publishing {
    publications {
        register("maven", MavenPublication::class) {
            val groupId: String by project
            val traceRecorderFatArtifactId: String by project
            val traceRecorderFatVersion: String by project

            this.groupId = groupId
            this.artifactId = traceRecorderFatArtifactId
            this.version = traceRecorderFatVersion

            artifact(tasks.named("traceRecorderFatJar"))

            configureMavenPublication {
                name.set(traceRecorderFatArtifactId)
                description.set("Lincheck trace recorder agent fat jar")
            }
        }
    }

    configureRepositories(
        artifactsRepositoryUrl = rootProject.run { uri(layout.buildDirectory.dir("artifacts/maven")) }
    )
}

configureSigning()
