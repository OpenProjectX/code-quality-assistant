package org.openprojectx.ai.plugin.testgen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentContextCollectorTest {

    @Test
    fun `parses maven coordinate version`() {
        assertEquals(
            "1.18.30",
            EnvironmentContextCollector.parseLibraryVersion("Maven: org.projectlombok:lombok:1.18.30", "lombok")
        )
    }

    @Test
    fun `parses gradle-style coordinate version`() {
        assertEquals(
            "3.24.2",
            EnvironmentContextCollector.parseLibraryVersion("Gradle: org.assertj:assertj-core:3.24.2", "assertj-core")
        )
    }

    @Test
    fun `parses bare coordinate without prefix`() {
        assertEquals(
            "5.11.4",
            EnvironmentContextCollector.parseLibraryVersion("org.junit.jupiter:junit-jupiter-api:5.11.4", "junit-jupiter-api")
        )
    }

    @Test
    fun `returns null when artifact does not match`() {
        assertNull(EnvironmentContextCollector.parseLibraryVersion("Maven: com.h2database:h2:2.2.224", "lombok"))
    }

    @Test
    fun `returns null for non-coordinate names`() {
        assertNull(EnvironmentContextCollector.parseLibraryVersion("some-random-name", "lombok"))
    }

    @Test
    fun `formats a full environment block`() {
        val info = EnvironmentInfo(
            languageLevel = "17",
            buildTool = "Maven",
            testLibraries = listOf("JUnit 5", "AssertJ", "Mockito (+ mockito-junit-jupiter)"),
            keyVersions = listOf("Lombok 1.18.30", "Spring Boot 3.2.5"),
            lombokPresent = true
        )

        val block = EnvironmentContextCollector.formatEnvironmentBlock(info)

        assertTrue(block.startsWith("## Test Environment"), "starts with heading")
        assertTrue(block.contains("Java language level: 17"))
        assertTrue(block.contains("Build tool: Maven"))
        assertTrue(block.contains("Test libraries available: JUnit 5, AssertJ, Mockito (+ mockito-junit-jupiter)"))
        assertTrue(block.contains("Key versions: Lombok 1.18.30, Spring Boot 3.2.5"))
        assertTrue(block.contains("Lombok present"), "includes the Lombok @Builder.Default caveat")
    }

    @Test
    fun `returns empty string when nothing detected`() {
        val info = EnvironmentInfo(
            languageLevel = null,
            buildTool = null,
            testLibraries = emptyList(),
            keyVersions = emptyList(),
            lombokPresent = false
        )

        assertEquals("", EnvironmentContextCollector.formatEnvironmentBlock(info))
    }

    @Test
    fun `warns when no test library markers are present`() {
        assertTrue(EnvironmentContextCollector.noTestLibrariesPresent(emptyList()))
    }

    @Test
    fun `does not warn when REST Assured is present`() {
        assertFalse(EnvironmentContextCollector.noTestLibrariesPresent(listOf("io.restassured.RestAssured")))
    }

    @Test
    fun `does not warn when JUnit 5 is present`() {
        assertFalse(EnvironmentContextCollector.noTestLibrariesPresent(listOf("org.junit.jupiter.api.Test")))
    }

    @Test
    fun `warns when only unrelated classes are present`() {
        assertTrue(EnvironmentContextCollector.noTestLibrariesPresent(listOf("com.h2database.Driver")))
    }
}
