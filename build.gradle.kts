plugins {
    `maven-publish`
}

allprojects {
    group = "org.openprojectx.ai.plugin"
    version = "0.1.0-snapshot"

    repositories {
        mavenCentral()
    }

    apply(plugin = "kotlin-jvm")
}