package org.openprojectx.ai.plugin

import java.nio.charset.StandardCharsets
import java.util.Base64

object SonarQubeAuth {
    fun authorizationHeader(config: SonarQubeConfig): String? = authorizationHeader(
        token = config.resolvedToken,
        username = config.username,
        password = config.resolvedPassword
    )

    fun authorizationHeader(token: String, username: String, password: String): String? {
        val normalizedToken = token.trim()
        if (normalizedToken.isNotBlank()) {
            return basic("$normalizedToken:")
        }

        val normalizedUsername = username.trim()
        val normalizedPassword = password.trim()
        if (normalizedUsername.isNotBlank() && normalizedPassword.isNotBlank()) {
            return basic("$normalizedUsername:$normalizedPassword")
        }

        return null
    }

    private fun basic(raw: String): String =
        "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
}
