package org.openprojectx.ai.plugin.core


enum class Framework(
    val id: String,
    val displayName: String
) {
    REST_ASSURED("restassured", "JUnit 5 + Rest Assured"),
    KARATE("karate", "Karate DSL");
}