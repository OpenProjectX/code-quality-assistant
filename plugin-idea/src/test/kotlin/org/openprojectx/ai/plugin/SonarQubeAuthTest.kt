package org.openprojectx.ai.plugin

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SonarQubeAuthTest {

    @Test
    fun `requires configured credentials before online SonarQube requests`() {
        val error = assertFailsWith<IllegalStateException> {
            SonarQubeAuth.requireAuthorizationHeader(
                SonarQubeConfig(
                    serverUrl = "https://sonarqube.example.com",
                    projectKey = "my-service"
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("Configure SonarQube Token/PAT"))
    }
}
