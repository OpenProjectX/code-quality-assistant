plugins {
    alias(libs.plugins.intellij)
    alias(libs.plugins.kotlinPluginSerialization)
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

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
