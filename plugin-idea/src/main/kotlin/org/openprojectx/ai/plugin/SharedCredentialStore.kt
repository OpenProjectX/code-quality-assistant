package org.openprojectx.ai.plugin

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * The single shared username/password entered in the basic settings view, used as the cross-service
 * fallback credential (LLM login, Bitbucket prompt repo, SonarQube). Stored in IntelliJ PasswordSafe
 * under one fixed key — never written to .ai-test.yaml.
 *
 * Tokens/PATs are intentionally NOT stored here: a token is service-specific and must never be shared
 * across services (see [SharedCredentialResolver]).
 */
object SharedCredentialStore {
    private const val SERVICE_NAME = "OpenProjectX.AI.SharedCredential"

    private val attributes: CredentialAttributes
        get() = CredentialAttributes(SERVICE_NAME)

    fun load(): SharedCredentialResolver.UsernamePassword {
        // Defensive: PasswordSafe requires a running Application. Outside one (e.g. plain unit tests)
        // treat the shared credential as absent rather than throwing.
        val creds = runCatching { PasswordSafe.instance.get(attributes) }.getOrNull()
        return SharedCredentialResolver.UsernamePassword(
            username = creds?.userName.orEmpty(),
            password = creds?.getPasswordAsString().orEmpty()
        )
    }

    fun save(username: String, password: String) {
        if (username.isBlank() && password.isBlank()) {
            clear()
            return
        }
        runCatching { PasswordSafe.instance.set(attributes, Credentials(username, password)) }
    }

    fun clear() {
        runCatching { PasswordSafe.instance.set(attributes, null) }
    }
}
