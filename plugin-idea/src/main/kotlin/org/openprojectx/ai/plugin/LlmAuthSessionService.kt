package org.openprojectx.ai.plugin

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.runBlocking
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.openprojectx.ai.plugin.llm.LlmUnauthorizedException
import org.openprojectx.ai.plugin.llm.TemplateRequestExecutor

@Service(Service.Level.PROJECT)
class LlmAuthSessionService(
    private val project: Project
) {
    private var sessionApiKey: String? = null

    fun resolve(settings: LlmSettings): LlmSettings {
        if (!settings.apiKey.isNullOrBlank()) {
            sessionApiKey = settings.apiKey
            return settings
        }

        val auth = settings.auth ?: return settings
        sessionApiKey?.let { return settings.copy(apiKey = it) }

        val credentials = promptCredentials(settings)
        val apiKey = runBlocking {
            TemplateRequestExecutor(HttpClients.shared(settings.httpDisableTlsVerification)).execute(
                config = auth.login,
                variables = mapOf(
                    "username" to credentials.username,
                    "password" to credentials.password,
                    "model" to settings.model,
                    "apiKey" to "",
                    "prompt" to "",
                    "promptJson" to "\"\""
                )
            )
        }.trim()

        if (apiKey.isBlank()) {
            error("LLM login returned an empty API key")
        }

        sessionApiKey = apiKey
        return settings.copy(apiKey = apiKey)
    }

    fun relogin(settings: LlmSettings): LlmSettings {
        if (settings.auth == null) {
            return settings
        }
        sessionApiKey = null
        return resolve(settings.copy(apiKey = null))
    }

    fun loginNow(): String {
        val settings = LlmSettingsLoader.load(project)
        val resolved = if (settings.auth != null) relogin(settings) else resolve(settings)
        return resolved.apiKey ?: error("LLM login did not produce an API key")
    }

    fun loginNowWithFeedback() {
        try {
            loginNow()
            Messages.showInfoMessage(project, "LLM login succeeded.", "AI Test Generator")
        } catch (e: Exception) {
            Messages.showErrorDialog(project, detailedErrorMessage("LLM login failed", e), "AI Test Generator")
        }
    }

    fun loadSavedLoginCredentialsForCurrentSettings(): LoginCredentials? {
        val settings = LlmSettingsLoader.load(project)
        return loadSavedCredentials(settings)?.let {
            LoginCredentials(
                username = it.userName.orEmpty(),
                password = it.getPasswordAsString().orEmpty(),
                remember = true
            )
        }?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
    }

    fun promptLoginCredentialsForCurrentSettings(): LoginCredentials {
        val settings = LlmSettingsLoader.load(project)
        return promptCredentials(settings)
    }

    fun withReloginOnUnauthorized(block: (LlmSettings) -> String): String {
        val baseSettings = LlmSettingsLoader.load(project)
        val resolved = resolve(baseSettings)

        return try {
            block(resolved)
        } catch (_: LlmUnauthorizedException) {
            if (baseSettings.auth == null) {
                throw LlmUnauthorizedException("Unauthorized LLM request and no login template is configured")
            }
            val refreshed = relogin(baseSettings)
            block(refreshed)
        }
    }

    private fun promptCredentials(settings: LlmSettings): LoginCredentials {
        lateinit var credentials: LoginCredentials
        val saved = loadSavedCredentials(settings)
        ApplicationManager.getApplication().invokeAndWait {
            val dialog = LlmLoginDialog(
                project = project,
                initialUsername = saved?.userName.orEmpty(),
                initialPassword = saved?.getPasswordAsString().orEmpty(),
                rememberByDefault = saved != null
            )
            if (!dialog.showAndGet()) {
                error("LLM login cancelled")
            }

            credentials = dialog.credentials()
            if (credentials.username.isBlank()) {
                error("LLM login requires a username")
            }
            if (credentials.password.isBlank()) {
                error("LLM login requires a password")
            }
            if (credentials.remember) {
                saveCredentials(settings, credentials)
            } else {
                clearSavedCredentials(settings)
            }
        }
        return credentials
    }

    private fun loadSavedCredentials(settings: LlmSettings): Credentials? {
        return PasswordSafe.instance.get(getCredentialAttributes(settings))
    }

    private fun saveCredentials(settings: LlmSettings, credentials: LoginCredentials) {
        PasswordSafe.instance.set(
            getCredentialAttributes(settings),
            Credentials(credentials.username, credentials.password)
        )
    }

    private fun clearSavedCredentials(settings: LlmSettings) {
        PasswordSafe.instance.set(getCredentialAttributes(settings), null)
    }

    private fun getCredentialAttributes(settings: LlmSettings): CredentialAttributes {
        val endpointKey = settings.endpoint ?: settings.auth?.login?.url ?: "default"
        val serviceName = "OpenProjectX.AI.Login.$endpointKey"
        return CredentialAttributes(serviceName)
    }

    companion object {
        fun getInstance(project: Project): LlmAuthSessionService = project.service()
    }

    private fun detailedErrorMessage(prefix: String, throwable: Throwable): String {
        val details = generateSequence(throwable) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf { msg -> msg.isNotEmpty() } }
            .distinct()
            .joinToString(" | caused by: ")
        return if (details.isBlank()) prefix else "$prefix: $details"
    }
}
