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
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
