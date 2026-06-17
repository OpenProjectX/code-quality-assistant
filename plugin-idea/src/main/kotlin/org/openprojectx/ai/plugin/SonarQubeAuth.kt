package org.openprojectx.ai.plugin

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import java.nio.charset.StandardCharsets
import java.util.Base64

object SonarQubeAuth {
    private const val CACHE_KEY_PREFIX = "OpenProjectX.AI.SonarQube"

    fun authorizationHeader(config: SonarQubeConfig): String? {
        // 1. Try config credentials first (token is service-specific; username/password may fall back
        //    to the shared credential).
        val token = config.resolvedToken
        val (user, pass) = effectiveUsernamePassword(config)
        val header = buildHeader(token, user, pass)
        if (header != null) {
            cacheCredentials(config.serverUrl, token, user, pass)
            return header
        }

        // 2. Fall back to PasswordSafe cache
        val cached = readCachedCredentials(config.serverUrl)
        if (cached != null) {
            return buildHeader(cached.first, cached.second, cached.third)
        }

        return null
    }

    fun authorizationHeader(token: String, username: String, password: String): String? =
        buildHeader(token, username, password)

    /**
     * Like [authorizationHeader] but fails fast when no credentials are configured, so callers
     * can surface an actionable message instead of silently sending an unauthenticated request.
     * Uses only the config-supplied credentials (no PasswordSafe lookup) so it is side-effect free.
     */
    fun requireAuthorizationHeader(config: SonarQubeConfig): String {
        val (user, pass) = effectiveUsernamePassword(config)
        return buildHeader(config.resolvedToken, user, pass)
            ?: throw IllegalStateException(
                "Configure SonarQube Token/PAT (or username + password) in Settings > AI Test Assistant " +
                    "before contacting ${config.serverUrl.ifBlank { "SonarQube" }}."
            )
    }

    /**
     * The username/password to authenticate with: the service's own when set, otherwise the shared
     * credential. The service token stays service-specific and is never sourced from the shared store —
     * when a token is present we keep the config's own username/password untouched.
     */
    private fun effectiveUsernamePassword(config: SonarQubeConfig): Pair<String, String> {
        if (config.resolvedToken.isNotBlank()) return config.username to config.resolvedPassword
        val shared = SharedCredentialStore.load()
        val resolved = SharedCredentialResolver.resolveUsernamePassword(
            serviceUsername = config.username,
            servicePassword = config.resolvedPassword,
            sharedUsername = shared.username,
            sharedPassword = shared.password
        )
        return resolved.username to resolved.password
    }

    private fun buildHeader(token: String, username: String, password: String): String? {
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

    private fun cacheKey(serverUrl: String): String =
        "$CACHE_KEY_PREFIX.${serverUrl.trimEnd('/').trim()}"

    private fun cacheCredentials(serverUrl: String, token: String, username: String, password: String) {
        val key = cacheKey(serverUrl)
        if (token.isNotBlank()) {
            PasswordSafe.instance.set(CredentialAttributes(key), Credentials(null, token))
        } else if (username.isNotBlank() && password.isNotBlank()) {
            PasswordSafe.instance.set(CredentialAttributes(key), Credentials(username, password))
        }
    }

    private fun readCachedCredentials(serverUrl: String): Triple<String, String, String>? {
        val creds = PasswordSafe.instance.get(CredentialAttributes(cacheKey(serverUrl))) ?: return null
        val user = creds.userName.orEmpty()
        val pass = creds.getPasswordAsString().orEmpty()
        if (pass.isBlank()) return null
        return if (user.isBlank()) {
            Triple(pass, "", "")
        } else {
            Triple("", user, pass)
        }
    }
}
