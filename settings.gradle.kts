plugins {
    // Allows Gradle to auto-download JDKs for toolchains (we compile/run with Java 21).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "LoadBalancer"

include("backend")
include("loadbalancer")
