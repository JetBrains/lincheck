import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
        val kotlinVersion: String by project
        val asmVersion: String by project
        val byteBuddyVersion: String by project

        api(project(":core"))
        api(project(":jvm-agent")) // should be alone as dependedency here
        compileOnly(project(":bootstrap"))

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
}

registerTraceAgentTasks()