plugins {
    java
}

group = "dev.chunkrescue"
version = "0.2.0-mvp"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Folia/Paper 26.1.2 public API. This keeps the plugin portable across Folia downstreams.
    // The API is compiled for the current platform Java level, so the build uses a Java 25 toolchain.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    processResources {
        filteringCharset = "UTF-8"
    }
}
