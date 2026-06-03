package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.util.Base64

object SonarQubeAuth {
    fun authorizationHeader(config: SonarQubeConfig): String? = authorizationHeader(
        token = config.resolvedToken,
        username = config.username,
        password = config.resolvedPassword
    )

    fun authorizationHeader(project: Project, config: SonarQubeConfig): String? {
        val directHeader = authorizationHeader(config)
        if (directHeader != null) return directHeader

        // Fall back to SSO token via AuthManager
        val ssoToken = try {
            AuthManager.getInstance(project).getToken("SonarQube",
                independentUsername = config.username.takeIf { it.isNotBlank() },
                independentPassword = config.resolvedPassword.takeIf { it.isNotBlank() })
        } catch (_: Exception) { "" }
        if (ssoToken.isNotBlank()) {
            return basic("$ssoToken:")
        }
        return null
    }

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
