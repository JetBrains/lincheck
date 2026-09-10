plugins {
    java
}

repositories {
    mavenCentral()
}

sourceSets.main {
    java.srcDirs("src/main")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(8)) }
}

tasks.jar {
    archiveFileName.set("agent-wrapper.jar")
}
