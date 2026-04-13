package org.openprojectx.ai.plugin

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

        val credentials = promptCredentials()
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
            Messages.showErrorDialog(project, e.message ?: e.toString(), "AI Test Generator")
        }
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

    private fun promptCredentials(): LoginCredentials {
        lateinit var credentials: LoginCredentials
        ApplicationManager.getApplication().invokeAndWait {
            val dialog = LlmLoginDialog(project)
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
        }
        return credentials
    }

    companion object {
        fun getInstance(project: Project): LlmAuthSessionService = project.service()
    }
}
