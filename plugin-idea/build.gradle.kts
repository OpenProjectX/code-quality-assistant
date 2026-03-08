plugins {
    alias(libs.plugins.intellij)
    alias(libs.plugins.kotlinPluginSerialization)
    `maven-publish`
    signing
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

val isCi: Boolean by gradle.extra

//intellij {
//    version.set("2024.3") // pick your target
//    type.set("IC")
//    plugins.set(listOf("yaml")) // YAML support; OpenAPI plugin is separate, but YAML is baseline
//}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set("253.*")
    }
}

dependencies {
    intellijPlatform {
        val type = providers.gradleProperty("platformType")
        val version = providers.gradleProperty("platformVersion")

        intellijIdea(version)
        bundledPlugins(listOf("org.jetbrains.plugins.yaml"))
    }

    implementation(project(":core"))
    implementation(project(":llm-client"))
    implementation(libs.ktor.client.contentneg)
    implementation(libs.ktor.serialization.json)
    implementation("io.ktor:ktor-client-logging:3.2.3")

    implementation(libs.bundles.kotlinxEcosystem)

    implementation("ch.qos.logback:logback-classic:1.5.32")
}

publishing {
    publications {
        create<MavenPublication>("pluginZip") {
            groupId = project.group.toString()
            artifactId = "ai-test-plugin"
            version = project.version.toString()

            artifact(tasks.named("buildPlugin")) {
                extension = "zip"
            }

            pom {
                name.set("AI Test Plugin")
                description.set("IntelliJ IDEA plugin ZIP distribution")
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

//    repositories {
//        mavenLocal()
//    }
}

signing {
    if (isCi) {
        val keyFile = System.getenv("SIGNING_KEY_FILE")
        val keyText = file(keyFile).readText()
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")

        useInMemoryPgpKeys(keyText, keyPass)

        sign(publishing.publications["pluginZip"])
    }

}