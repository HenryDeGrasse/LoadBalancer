plugins {
    application
    java
}

dependencies {
    testImplementation(project(":backend"))
}

application {
    mainClass.set("lb.lb.LoadBalancer")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
