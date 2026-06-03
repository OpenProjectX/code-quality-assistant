package org.openprojectx.ai.plugin

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.openprojectx.ai.plugin.llm.LlmRuntimeLogger
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.openprojectx.ai.plugin.llm.LlmUnauthorizedException

@Service(Service.Level.PROJECT)
class LlmAuthSessionService(
    private val project: Project
) {
    private val authManager: AuthManager get() = AuthManager.getInstance(project)

    private fun resolve(settings: LlmSettings): LlmSettings {
        installRuntimeLogSink()
        if (!settings.apiKey.isNullOrBlank()) {
            return settings
        }
        if (settings.auth == null) return settings
        val token = authManager.getToken("LLM")
        return settings.copy(apiKey = token)
    }

    fun loginNow(): String {
        installRuntimeLogSink()
        val settings = LlmSettingsLoader.load(project)
        val token = authManager.getToken("LLM")
        return token.ifBlank { error("SSO login did not produce a token") }
    }

    fun loginNowWithFeedback() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                loginNow()
                ApplicationManager.getApplication().invokeLater({
                    Messages.showInfoMessage(project, "SSO login succeeded.", "Code Quality Assistant")
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog(project, detailedErrorMessage("SSO login failed", e), "Code Quality Assistant")
                }, ModalityState.any())
            }
        }
    }

    fun loadSavedLoginCredentialsForCurrentSettings(): LoginCredentials? {
        val creds = PasswordSafe.instance.get(CredentialAttributes("OpenProjectX.AI.SSO.Credentials"))
        return creds?.let {
            LoginCredentials(
                username = it.userName.orEmpty(),
                password = it.getPasswordAsString().orEmpty(),
                remember = true
            )
        }?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
    }

    fun promptLoginCredentialsForCurrentSettings(): LoginCredentials {
        // This is only used by settings UI; trigger a full login and return what the user entered
        val token = authManager.getToken("LLM")  // triggers login dialog if needed
        val creds = PasswordSafe.instance.get(CredentialAttributes("OpenProjectX.AI.SSO.Credentials"))
        return LoginCredentials(
            username = creds?.userName.orEmpty(),
            password = creds?.getPasswordAsString().orEmpty(),
            remember = true
        ).takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
            ?: LoginCredentials(token.take(20), "", remember = false) // fallback
    }

    fun withReloginOnUnauthorized(block: (LlmSettings) -> String): String {
        val baseSettings = LlmSettingsLoader.load(project)
        val resolved = resolve(baseSettings)

        return try {
            block(resolved)
        } catch (_: LlmUnauthorizedException) {
            if (baseSettings.auth == null) {
                if (baseSettings.apiKey.isNullOrBlank()) {
                    throw LlmUnauthorizedException("Unauthorized LLM request and no SSO login template is configured")
                }
                throw LlmUnauthorizedException("Unauthorized LLM request — your API key may be invalid or expired. Update the key in .ai-test.yaml or configure a login template for automatic renewal.")
            }
            val newToken = authManager.onUnauthorized("LLM")
            block(resolve(baseSettings).copy(apiKey = newToken))
        }
    }

    private fun installRuntimeLogSink() {
        LlmRuntimeLogger.sink = { message -> RuntimeLogStore.append(message) }
    }

    private fun detailedErrorMessage(prefix: String, throwable: Throwable): String {
        val details = generateSequence(throwable) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf { msg -> msg.isNotEmpty() } }
            .distinct()
            .joinToString(" | caused by: ")
        return if (details.isBlank()) prefix else "$prefix: $details"
    }

    companion object {
        fun getInstance(project: Project): LlmAuthSessionService = project.service()
    }
}
