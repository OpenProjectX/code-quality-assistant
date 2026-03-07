plugins {
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.ktor.client.okhttp)
    implementation("org.yaml:snakeyaml:2.6")
}
