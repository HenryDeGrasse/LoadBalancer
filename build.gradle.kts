plugins {
    // empty on purpose; subprojects declare what they need
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    // Standardize on Java 21 regardless of the Gradle runtime JDK
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension>("java") {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        dependencies {
            // Test with JUnit 5 across modules
            add("testImplementation", "org.junit.jupiter:junit-jupiter-api:5.10.2")
            add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine:5.10.2")
            // Needed by Gradle test execution on some setups
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.10.2")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
