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
        val junitVersion: String by project

        compileOnly(project(":bootstrap"))
        implementation(project(":common"))
        implementation(project(":jvm-agent"))
        implementation(project(":trace"))
        implementation(project(":tracing-agent"))

        api(kotlin("reflect"))
        api("org.ow2.asm:asm-commons:${asmVersion}")
        api("org.ow2.asm:asm-util:${asmVersion}")

        testImplementation("junit:junit:$junitVersion")
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

// The agent-side expression compilers ship as nested jar resources — like `bootstrap.jar`,
// never unpacked into the fat-jar root. The agent loads each in an isolated class loader
// on first use, so compiler classes never touch the instrumented application's class path.
// The Kotlin compiler does not bundle its runtime, so its dependency set is merged into one jar.
val kotlinExpressionCompiler: Configuration by configurations.creating
val java8ExpressionCompiler: Configuration by configurations.creating
val java11ExpressionCompiler: Configuration by configurations.creating
val java17ExpressionCompiler: Configuration by configurations.creating

dependencies {
    val kotlinVersion: String by project
    val ecjJdk8Version: String by project
    val ecjJdk11Version: String by project
    val ecjJdk17Version: String by project
    kotlinExpressionCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    java8ExpressionCompiler("org.eclipse.jdt:ecj:$ecjJdk8Version")
    java11ExpressionCompiler("org.eclipse.jdt:ecj:$ecjJdk11Version")
    java17ExpressionCompiler("org.eclipse.jdt:ecj:$ecjJdk17Version")
}

val kotlinExpressionCompilerJar = tasks.register<Zip>("kotlinExpressionCompilerJar") {
    dependsOn(":jvm-agent:kotlinExpressionAnalyzerJar")
    archiveFileName.set("kotlin-expression-compiler.jar")
    destinationDirectory.set(layout.buildDirectory.dir("kotlinExpressionCompiler"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ kotlinExpressionCompiler.files.map(::zipTree) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
    }
    from({
        zipTree(layout.projectDirectory.file("../jvm-agent/build/libs/kotlin-expression-analyzer.jar"))
    })
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
