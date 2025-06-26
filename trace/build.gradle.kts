plugins {
    java
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java.srcDirs("src/main")
    }

    test {
        java.srcDir("src/test")

        resources {
            srcDir("src/test/resources")
        }
    }
}

dependencies {

}