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
import kotlinx.coroutines.runBlocking
import org.openprojectx.ai.plugin.llm.LlmRuntimeLogger
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.openprojectx.ai.plugin.llm.LlmUnauthorizedException
import org.openprojectx.ai.plugin.llm.TemplateRequestExecutor

@Service(Service.Level.PROJECT)
class LlmAuthSessionService(
    private val project: Project
) {
    private var sessionApiKey: String? = null

    fun resolve(settings: LlmSettings): LlmSettings {
        installRuntimeLogSink()
        if (!settings.apiKey.isNullOrBlank()) {
            sessionApiKey = settings.apiKey
            return settings
        }

        val auth = settings.auth ?: return settings
        sessionApiKey?.let { return settings.copy(apiKey = it) }

        val credentials = promptCredentials(settings)
        val apiKey = runBlocking {
            TemplateRequestExecutor(
                HttpClients.shared(
                    disableTlsVerification = settings.httpDisableTlsVerification,
                    timeoutSeconds = settings.timeoutSeconds
                )
            ).execute(
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
        installRuntimeLogSink()
        val settings = LlmSettingsLoader.load(project)
        val resolved = if (settings.auth != null) relogin(settings) else resolve(settings)
        return resolved.apiKey ?: error("LLM login did not produce an API key")
    }

    fun loginNowWithFeedback() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                loginNow()
                ApplicationManager.getApplication().invokeLater({
                    Messages.showInfoMessage(project, "LLM login succeeded.", "Code Quality Improver")
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog(project, detailedErrorMessage("LLM login failed", e), "Code Quality Improver")
                }, ModalityState.any())
            }
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
        val loginAlreadyAttempted = baseSettings.auth != null && baseSettings.apiKey.isNullOrBlank()

        return try {
            block(resolved)
        } catch (_: LlmUnauthorizedException) {
            if (baseSettings.auth == null) {
                if (baseSettings.apiKey.isNullOrBlank()) {
                    throw LlmUnauthorizedException("Unauthorized LLM request and no API key or login template is configured")
                }
                throw LlmUnauthorizedException("Unauthorized LLM request — your API key may be invalid or expired. Update the key in .ai-test.yaml or configure a login template for automatic renewal.")
            }
            if (loginAlreadyAttempted) {
                throw LlmUnauthorizedException("Unauthorized LLM request after login attempt; please verify login endpoint/credentials")
            }
            val refreshed = relogin(baseSettings)
            block(refreshed)
        }
    }

    private fun installRuntimeLogSink() {
        LlmRuntimeLogger.sink = { message -> RuntimeLogStore.append(message) }
    }

    private fun promptCredentials(settings: LlmSettings): LoginCredentials {
        lateinit var credentials: LoginCredentials
        val saved = loadSavedCredentials(settings)
        val showDialog = {
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

        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            showDialog()
        } else {
            app.invokeAndWait(showDialog, ModalityState.any())
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
