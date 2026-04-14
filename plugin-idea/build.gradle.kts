plugins {
    alias(libs.plugins.intellij)
    alias(libs.plugins.kotlinPluginSerialization)
    `maven-publish`
    signing
}

val restrictedNetworkMode = providers.gradleProperty("restrictedNetworkMode")
    .map(String::toBooleanStrictOrNull)
    .orElse(
        providers.environmentVariable("RESTRICTED_NETWORK_MODE")
            .map(String::toBooleanStrictOrNull)
    )
    .getOrElse(false)
val localIntellijPlatformPath = providers.gradleProperty("intellijPlatformLocalPath")
    .orElse(providers.environmentVariable("INTELLIJ_PLATFORM_LOCAL_PATH"))
    .orNull

repositories {
    intellijPlatform {
        if (!restrictedNetworkMode && localIntellijPlatformPath == null) {
            defaultRepositories()
        }
        intellijDependencies()
        localPlatformArtifacts()
    }
}

val isCi: Boolean by gradle.extra

val buildPluginJar by tasks.registering(Zip::class) {
    group = "build"
    description = "Builds the IntelliJ plugin distribution as JAR"

    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
    archiveExtension.set("jar")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from({
        zipTree(tasks.named("buildPlugin").get().outputs.files.singleFile)
    })

    dependsOn(tasks.named("buildPlugin"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("253.*")
    }

    assemble {
        dependsOn(buildPlugin)
        dependsOn(buildPluginJar)
    }

    named("runIde") {
        dependsOn(":fake-login-server:startFakeLoginServer")
        finalizedBy(":fake-login-server:stopFakeLoginServer")
    }

//    withType<JavaExec>().configureEach {
//        if (name == "runIde") {
//            doFirst("removeLegacyCoroutinesJavaagent") {
//                val filteredArgs = allJvmArgs.filterNot {
//                    it.contains("coroutines-javaagent-legacy.jar")
//                }
//                if (filteredArgs.size != allJvmArgs.size) {
//                    logger.lifecycle("Removed incompatible coroutines-javaagent-legacy from runIde JVM args.")
//                    allJvmArgs = filteredArgs
//                }
//            }
//        }
//    }
}

intellijPlatform {
    publishing {
        token = System.getenv("JETBRAINS_TOKEN")
//        channels = listOf("stable")
    }
}


dependencies {
    intellijPlatform {
        val type = providers.gradleProperty("platformType").getOrElse("IC")
        val version = providers.gradleProperty("platformVersion").getOrElse("2025.2")

        if (localIntellijPlatformPath != null) {
            logger.info("using local platform: $localIntellijPlatformPath")
            local(localIntellijPlatformPath)
        } else {
            create(type, version)
        }
        instrumentationTools()
        bundledPlugins(listOf("org.jetbrains.plugins.yaml","Git4Idea"))
    }

    implementation(project(":core"))
    implementation(project(":llm-client"))
    implementation(libs.ktor.client.contentneg)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.okhttp)

    implementation("io.ktor:ktor-client-logging:3.2.3")

    implementation(libs.bundles.kotlinxEcosystem)

    implementation("ch.qos.logback:logback-classic:1.5.32")
}

publishing {
    publications {
        create<MavenPublication>("pluginDistribution") {
            groupId = project.group.toString()
            artifactId = "ai-test-plugin"
            version = project.version.toString()

            artifact(buildPluginJar) {
                extension = "jar"
            }

            artifact(tasks.named("buildPlugin")) {
                classifier = "plugin"
                extension = "zip"
            }

            pom {
                name.set("AI Test Plugin")
                description.set("IntelliJ IDEA plugin distribution")
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

signing {
    if (isCi) {
        val keyFile = System.getenv("SIGNING_KEY_FILE")
        val keyText = file(keyFile).readText()
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")

        useInMemoryPgpKeys(keyText, keyPass)
        sign(publishing.publications["pluginDistribution"])
    }
}
