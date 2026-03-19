plugins {
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

allprojects {
    group = "org.openprojectx.ai.plugin"
    version = "0.1.4"

    repositories {
        mavenCentral()
    }

    apply(plugin = "kotlin-jvm")
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

//            username.set(providers.gradleProperty("sonatypeUsername"))
//            password.set(providers.gradleProperty("sonatypePassword"))
            username.set(System.getenv("OSSRH_USERNAME"))
            password.set(System.getenv("OSSRH_PASSWORD"))

        }
    }
}