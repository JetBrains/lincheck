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

        compileOnly(project(":bootstrap"))
        api(project(":trace")) // TODO: can this be fixed?

        api("org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-stdlib-common:${kotlinVersion}")
        api("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
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