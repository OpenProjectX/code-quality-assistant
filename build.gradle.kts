plugins {
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

fun localRepositoryDirectories() = sequenceOf(
    providers.gradleProperty("localRepositoryPaths").orNull,
    providers.environmentVariable("LOCAL_REPOSITORY_PATHS").orNull
)
    .filterNotNull()
    .flatMap { it.split(',').asSequence() }
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map(::file)
    .distinctBy { it.absoluteFile.normalize() }
    .toList()

allprojects {
    group = "org.openprojectx.ai.plugin"
    version = "0.1.4"

    repositories {
        localRepositoryDirectories().forEach { repoDir ->
            maven(url = repoDir.toURI())
        }
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
