plugins {
    application
    java
}

application {
    mainClass.set("lb.backend.BackendServer")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
