import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

plugins {
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("net.researchgate.release") version "3.1.0" apply false
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

val gitTrackedPaths = providers.exec {
    workingDir(rootDir)
    commandLine("git", "ls-files", "-z")
}.standardOutput.asText.map { output ->
    output
        .split('\u0000')
        .filter(String::isNotBlank)
        .toSet()
}

val sourcesJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages all Git-tracked files from the repository as a sources JAR."

    archiveClassifier.set("sources")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(rootDir) {
        includeEmptyDirs = false
        exclude { element ->
            !element.isDirectory && element.relativePath.pathString !in gitTrackedPaths.get()
        }
    }
}

allprojects {
    group = "org.openprojectx.ai.plugin"
    version = rootProject.version

    repositories {
        localRepositoryDirectories().forEach { repoDir ->
            maven(url = repoDir.toURI())
        }
        mavenCentral()
    }

    apply(plugin = "kotlin-jvm")
}

publishing {
    publications {
        create<MavenPublication>("rootModule") {
            artifactId = "${rootProject.name}-root"
            from(components["java"])
            artifact(sourcesJar)

            pom {
                name.set("AI Test Plugin Root Module")
                description.set("Root Maven publication for the AI Test Plugin build")
                url.set("https://github.com/OpenProjectX/ai-test-plugin")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("OpenProjectX")
                        name.set("OpenProjectX")
                        email.set("admin@openprojectx.org")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/OpenProjectX/ai-test-plugin.git")
                    developerConnection.set("scm:git:ssh://git@github.com:OpenProjectX/ai-test-plugin.git")
                    url.set("https://github.com/OpenProjectX/ai-test-plugin.git")
                }
            }
        }
    }
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

signing {
    val isCi: Boolean by gradle.extra
    if (isCi) {
        val keyFile = System.getenv("SIGNING_KEY_FILE")
        if (keyFile != null) {
            val keyText = file(keyFile).readText()
            val keyPass = System.getenv("SIGNING_KEY_PASSWORD")

            useInMemoryPgpKeys(keyText, keyPass)
            sign(publishing.publications["rootModule"])
        }
    }
}

// Only apply the release plugin when actually running the release task and in the root build.
//
// Two guards are needed:
//  1. gradle.parent == null  — the release plugin's GradleBuild task spawns a nested invocation
//     that re-evaluates this script; applying the plugin there would register its incompatible
//     BuildEventsListenerRegistry listener in the nested build and break config cache there.
//  2. taskNames contains "release" — the plugin registers its listener at apply-time, not
//     task-execution-time, so applying it unconditionally poisons the config cache for every
//     other build (clean, build, etc.) even when the release task is never run.
val isReleaseBuild = gradle.parent == null &&
    gradle.startParameter.taskNames.any { it == "release" || it.endsWith(":release") }

if (isReleaseBuild) {
    apply(plugin = "net.researchgate.release")

    configure<net.researchgate.release.ReleaseExtension> {
        // Build the IntelliJ plugin distribution as the release build step.
        buildTasks.set(listOf(":plugin-idea:publishPlugin"))
        // Tag format: e.g. "0.1.8"
        tagTemplate.set("\${version}")
        git {
            requireBranch.set("main")
        }
    }

    // Belt-and-suspenders: also mark the release task itself as config-cache-incompatible
    // so Gradle skips caching for the outer release build entirely.
    tasks.named("release") {
        notCompatibleWithConfigurationCache("net.researchgate.release plugin is not configuration cache compatible")
    }
}
