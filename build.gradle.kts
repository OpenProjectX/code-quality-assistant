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

// Drive release through an explicit nested build.
//
// The researchgate release plugin is not configuration-cache compatible on Gradle 9.x,
// and its default outer "release" task spawns another GradleBuild that can recurse into
// a second nested build. The failure shows up as:
//   :ai-test-plugin-release:ai-test-plugin-release:beforeReleaseBuild not found
//
// To keep the plugin contained, the outer build defines its own GradleBuild task and only
// applies the plugin inside the nested "<root>-release" build.

val releaseLifecycleTasks = listOf(
    ":createScmAdapter",
    ":initScmAdapter",
    ":checkCommitNeeded",
    ":checkUpdateNeeded",
    ":checkoutMergeToReleaseBranch",
    ":unSnapshotVersion",
    ":confirmReleaseVersion",
    ":checkSnapshotDependencies",
    ":beforeReleaseBuild",
    ":plugin-idea:publishPlugin",
    ":afterReleaseBuild",
    ":preTagCommit",
    ":createReleaseTag",
    ":checkoutMergeFromReleaseBranch",
    ":updateVersion",
    ":commitNewVersion"
)

val isNestedReleaseBuild = gradle.parent != null &&
    providers.gradleProperty("release.releasing").orNull == "true"

if (isNestedReleaseBuild) {
    apply(plugin = "net.researchgate.release")

    configure<net.researchgate.release.ReleaseExtension> {
        // The nested build already runs the release lifecycle. Clearing buildTasks prevents
        // runBuildTasks from spawning a second nested build.
        buildTasks.set(emptyList())
        tagTemplate.set("\${version}")
        git {
            requireBranch.set("main")
        }
    }

    tasks.configureEach {
        notCompatibleWithConfigurationCache("net.researchgate.release nested build is not configuration cache compatible")
    }
} else {
    tasks.register<GradleBuild>("release") {
        group = "Release"
        description = "Verify project, release, and update version to next."

        buildName = "${project.name}-release"
        dir = rootDir
        setTasks(releaseLifecycleTasks)

        startParameter = gradle.startParameter.newBuild().apply {
            setTaskNames(releaseLifecycleTasks)
            projectProperties = projectProperties +
                mapOf(
                    "org.gradle.configuration-cache" to "false",
                    "release.releasing" to "true"
                )
            systemPropertiesArgs = systemPropertiesArgs + ("org.gradle.configuration-cache" to "false")
        }

        notCompatibleWithConfigurationCache("release uses a nested Gradle build with configuration cache disabled")
    }
}
