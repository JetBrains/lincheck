plugins {
    java
}

repositories {
    mavenCentral()
}

sourceSets.main {
    java.srcDirs("src")
}

tasks.jar {
    archiveFileName.set("bootstrap.jar")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(8)) }
}
