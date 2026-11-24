plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "examples"

includeBuild("..") {
    dependencySubstitution {
        substitute(module("org.jetbrains.lincheck:lincheck")).using(project(":lincheck"))
    }
}
