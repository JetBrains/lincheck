import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("maven-publish")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

// The compilers the agent-side expression tests load in isolation.
// In the packaged agent the same jars are nested resources instead.
val kotlinExpressionCompilerForTests: Configuration by configurations.creating
val java8ExpressionCompilerForTests: Configuration by configurations.creating
val java11ExpressionCompilerForTests: Configuration by configurations.creating
val java17ExpressionCompilerForTests: Configuration by configurations.creating

sourceSets {
    main {
        java.srcDirs("src/main")
    }

    test {
        java.srcDirs("src/test")
    }

    create("kotlinExpressionAnalyzer") {
        java.srcDirs("src/kotlin-expression-analyzer")
        compileClasspath += kotlinExpressionCompilerForTests
    }
}

dependencies {
    val asmVersion: String by project
    val byteBuddyVersion: String by project
    compileOnly(project(":bootstrap"))
    implementation(project(":common"))

    api(kotlin("reflect"))
    api("org.ow2.asm:asm-commons:${asmVersion}")
    api("org.ow2.asm:asm-util:${asmVersion}")
    api("net.bytebuddy:byte-buddy:${byteBuddyVersion}")
    api("net.bytebuddy:byte-buddy-agent:${byteBuddyVersion}")

    // Structural source generation for agent-compiled expression wrappers.
    val javaPoetVersion: String by project
    val kotlinPoetVersion: String by project
    implementation("com.squareup:javapoet:${javaPoetVersion}")
    implementation("com.squareup:kotlinpoet-jvm:${kotlinPoetVersion}")

    val junitVersion: String by project
    testImplementation("junit:junit:$junitVersion")

    // Bootstrap classes (sun.nio.ch.lincheck.Injections, BreakpointStorage,
    // ThreadDescriptor) are required when a unit test exercises the
    // class-file transformer end-to-end; otherwise NoClassDefFoundError.
    testImplementation(project(":bootstrap"))

    val kotlinVersion: String by project
    val ecjJdk8Version: String by project
    val ecjJdk11Version: String by project
    val ecjJdk17Version: String by project
    // Transitive: the embeddable compiler does not bundle the Kotlin runtime it needs.
    kotlinExpressionCompilerForTests("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    java8ExpressionCompilerForTests("org.eclipse.jdt:ecj:$ecjJdk8Version")
    java11ExpressionCompilerForTests("org.eclipse.jdt:ecj:$ecjJdk11Version")
    java17ExpressionCompilerForTests("org.eclipse.jdt:ecj:$ecjJdk17Version")
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
        getAccessToInternalDefinitionsOf(project(":common"))
    }

    withType<Jar> {
        dependsOn(":bootstrapJar")
    }
}

val kotlinExpressionAnalyzerJar = tasks.register<Jar>("kotlinExpressionAnalyzerJar") {
    from(sourceSets["kotlinExpressionAnalyzer"].output)
    archiveFileName.set("kotlin-expression-analyzer.jar")
}

tasks {
    test {
        dependsOn(kotlinExpressionAnalyzerJar)
        configureJvmTestCommon(project)
        val compilerJars = kotlinExpressionCompilerForTests + files(kotlinExpressionAnalyzerJar)
        doFirst {
            systemProperty(
                "lincheck.kotlinCompilerJar",
                compilerJars.files.joinToString(File.pathSeparator) { it.absolutePath },
            )
            systemProperty("lincheck.javaCompilerJar.8", java8ExpressionCompilerForTests.singleFile.absolutePath)
            systemProperty("lincheck.javaCompilerJar.11", java11ExpressionCompilerForTests.singleFile.absolutePath)
            systemProperty("lincheck.javaCompilerJar.17", java17ExpressionCompilerForTests.singleFile.absolutePath)
        }
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
