package org.openprojectx.ai.plugin.core


enum class Framework(
    val id: String,
    val displayName: String
) {
    REST_ASSURED("restassured", "JUnit 5 + Rest Assured"),
    KARATE("karate", "Karate DSL");

    companion object {
        fun fromIdOrNull(value: String): Framework? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.id == normalized }
        }
    }

    override fun toString(): String = displayName
}