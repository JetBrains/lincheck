import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java.srcDirs("src/main")
    }

    dependencies {
        val asmVersion: String by project
        val byteBuddyVersion: String by project

        compileOnly(project(":bootstrap"))
        implementation(project(":common"))
        implementation(project(":jvm-agent"))
        implementation(project(":trace"))

        api(kotlin("reflect"))
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
        getAccessToInternalDefinitionsOf(
            project(":common"),
            project(":trace")
        )
    }
}

val jar = tasks.jar {
    archiveFileName.set("tracer.jar")
}