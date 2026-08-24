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

    test {
        java.srcDir("src/test")
    }

    dependencies {
        // main
        val asmVersion: String by project
        val byteBuddyVersion: String by project
        val junitVersion: String by project

        compileOnly(project(":bootstrap"))
        implementation(project(":common"))
        implementation(project(":jvm-agent"))
        implementation(project(":trace"))
        implementation(project(":tracing-agent"))

        api(kotlin("reflect"))
        api("org.ow2.asm:asm-commons:${asmVersion}")
        api("org.ow2.asm:asm-util:${asmVersion}")
        api("net.bytebuddy:byte-buddy:${byteBuddyVersion}")
        api("net.bytebuddy:byte-buddy-agent:${byteBuddyVersion}")

        testImplementation("junit:junit:$junitVersion")
    }
}

setupTestsJDK(project)

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

    test {
        configureJvmTestCommon(project)
    }
}

registerTraceAgentTasks(
    fatJarName = "app-glass-agent",
    fatJarTaskName = "liveDebuggerFatJar",
    premainClass = "org.jetbrains.lincheck.livedebugger.LiveDebuggerAgent"
)

publishing {
    publications {
        register("maven", MavenPublication::class) {
            val liveDebuggerFatArtifactId: String by project
            val liveDebuggerFatVersion: String by project

            this.groupId = "org.jetbrains.appglass"
            this.artifactId = liveDebuggerFatArtifactId
            this.version = liveDebuggerFatVersion

            artifact(tasks.named("liveDebuggerFatJar"))

            configureMavenPublication {
                name.set(liveDebuggerFatArtifactId)
                description.set("Lincheck live debugger agent fat jar")
            }
        }
    }

    configureRepositories(
        artifactsRepositoryUrl = rootProject.run { uri(layout.buildDirectory.dir("artifacts/maven")) }
    )

    repositories {
        maven {
            name = "appGlass"
            url = uri("https://packages.jetbrains.team/maven/p/ag/app-glass-maven")

            credentials {
                username = System.getenv("SPACE_USERNAME")
                password = System.getenv("SPACE_PASSWORD")
            }
        }
    }
}

configureSigning()
