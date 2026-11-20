import java.util.zip.ZipFile
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

// Compilers are loaded in isolation by tests and nested as resources in the packaged agent.
val kotlinExpressionCompiler: Configuration by configurations.creating
val java8ExpressionCompiler: Configuration by configurations.creating
val java11ExpressionCompiler: Configuration by configurations.creating
val java17ExpressionCompiler: Configuration by configurations.creating

sourceSets {
    create("kotlinExpressionAnalyzer") {
        java.srcDirs("src/kotlin-expression-analyzer")
        compileClasspath += kotlinExpressionCompiler
    }
    main {
        java.srcDirs("src/main")
    }

    test {
        java.srcDir("src/test")
    }

    dependencies {
        // main
        // Structural source generation for agent-compiled expression wrappers.
        val javaPoetVersion: String by project
        val kotlinPoetVersion: String by project
        implementation("com.squareup:javapoet:${javaPoetVersion}")
        implementation("com.squareup:kotlinpoet-jvm:${kotlinPoetVersion}")

        val kotlinVersion: String by project
        val ecjJdk8Version: String by project
        val ecjJdk11Version: String by project
        val ecjJdk17Version: String by project
        // Transitive: the embeddable compiler does not bundle the Kotlin runtime it needs.
        kotlinExpressionCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
        java8ExpressionCompiler("org.eclipse.jdt:ecj:$ecjJdk8Version")
        java11ExpressionCompiler("org.eclipse.jdt:ecj:$ecjJdk11Version")
        java17ExpressionCompiler("org.eclipse.jdt:ecj:$ecjJdk17Version")
        val asmVersion: String by project
        val junitVersion: String by project

        compileOnly(project(":bootstrap"))
        implementation(project(":common"))
        implementation(project(":jvm-agent"))
        implementation(project(":trace"))
        implementation(project(":live-debugger-protocol"))
        implementation(project(":tracing-agent"))

        api(kotlin("reflect"))
        api("org.ow2.asm:asm-commons:${asmVersion}")
        api("org.ow2.asm:asm-util:${asmVersion}")

        testImplementation("junit:junit:$junitVersion")
        testImplementation(project(":bootstrap"))
    }
}

setupTestsJDK(project)

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
            project(":tracing-agent"),
            project(":jvm-agent")
        )
    }

}

registerTraceAgentTasks(
    fatJarName = "app-glass-agent",
    fatJarTaskName = "liveDebuggerFatJar",
    premainClass = "org.jetbrains.lincheck.livedebugger.LiveDebuggerAgent"
)

val kotlinExpressionAnalyzerJar = tasks.register<Jar>("kotlinExpressionAnalyzerJar") {
    from(sourceSets["kotlinExpressionAnalyzer"].output)
    archiveFileName.set("kotlin-expression-analyzer.jar")
}

tasks {
    test {
        dependsOn(kotlinExpressionAnalyzerJar)
        configureJvmTestCommon(project)
        val compilerJars = kotlinExpressionCompiler + files(kotlinExpressionAnalyzerJar)
        doFirst {
            systemProperty(
                "lincheck.kotlinCompilerJar",
                compilerJars.files.joinToString(File.pathSeparator) { it.absolutePath },
            )
            systemProperty("lincheck.javaCompilerJar.8", java8ExpressionCompiler.singleFile.absolutePath)
            systemProperty("lincheck.javaCompilerJar.11", java11ExpressionCompiler.singleFile.absolutePath)
            systemProperty("lincheck.javaCompilerJar.17", java17ExpressionCompiler.singleFile.absolutePath)
        }
    }
}

val kotlinExpressionCompilerJar = tasks.register<Zip>("kotlinExpressionCompilerJar") {
    dependsOn(kotlinExpressionAnalyzerJar)
    archiveFileName.set("kotlin-expression-compiler.jar")
    destinationDirectory.set(layout.buildDirectory.dir("kotlinExpressionCompiler"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ kotlinExpressionCompiler.files.map(::zipTree) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
    }
    from({
        zipTree(kotlinExpressionAnalyzerJar.get().archiveFile)
    })
}

// The analyzer is compiled separately against compiler internals and must travel inside the isolated compiler jar.
tasks.named("liveDebuggerFatJarVerify") {
    doLast {
        ZipFile(kotlinExpressionCompilerJar.get().archiveFile.get().asFile).use { compiler ->
            check(compiler.getEntry(
                "org/jetbrains/lincheck/livedebugger/expressions/kotlin/compiler/KotlinExpressionAnalyzer.class"
            ) != null) { "The nested Kotlin compiler is missing its expression analyzer" }
        }
    }
}

fun javaExpressionCompilerJar(
    taskName: String,
    archiveName: String,
    compiler: Configuration,
) = tasks.register<Jar>(taskName) {
    archiveFileName.set(archiveName)
    destinationDirectory.set(layout.buildDirectory.dir("javaExpressionCompiler"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ compiler.files.map(::zipTree) }) {
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "module-info.class",
            "META-INF/versions/*/module-info.class",
        )
    }
}

val java8ExpressionCompilerJar =
    javaExpressionCompilerJar(
        "java8ExpressionCompilerJar",
        "java-expression-compiler-jdk8.jar",
        java8ExpressionCompiler,
    )
val java11ExpressionCompilerJar =
    javaExpressionCompilerJar(
        "java11ExpressionCompilerJar",
        "java-expression-compiler-jdk11.jar",
        java11ExpressionCompiler,
    )
val java17ExpressionCompilerJar =
    javaExpressionCompilerJar(
        "java17ExpressionCompilerJar",
        "java-expression-compiler-jdk17.jar",
        java17ExpressionCompiler,
    )

// The fat jar exposes only the dependency-free wrapper. Its nested agent payload is loaded in
// an isolated class loader, so compiler jars belong there rather than at the fat-jar root.
tasks.named<Jar>("liveDebuggerFatJarPayload") {
    from(kotlinExpressionCompilerJar)
    from(java8ExpressionCompilerJar)
    from(java11ExpressionCompilerJar)
    from(java17ExpressionCompilerJar)
}

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
