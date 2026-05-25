plugins {
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.ktor.client.okhttp)
    implementation("org.yaml:snakeyaml:2.6")
    implementation("com.github.jknack:handlebars:4.4.0")
    implementation("com.jayway.jsonpath:json-path:2.9.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
