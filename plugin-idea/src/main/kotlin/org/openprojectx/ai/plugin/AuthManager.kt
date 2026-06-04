package org.openprojectx.ai.plugin

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.openprojectx.ai.plugin.llm.TemplateRequestConfig
import org.openprojectx.ai.plugin.llm.TemplateRequestExecutor

@Service(Service.Level.PROJECT)
class AuthManager(private val project: Project) {

    companion object {
        fun getInstance(project: Project): AuthManager = project.service()

        private const val SSO_CREDENTIALS_KEY = "OpenProjectX.AI.SSO.Credentials"
        private fun serviceTokenKey(service: String) = "OpenProjectX.AI.SSO.$service.Token"
    }

    // ---- Public API ----

    /** Get token for a service. Priority: service-specific → shared SSO → login */
    fun getToken(
        service: String,
        independentLogin: TemplateRequestConfig? = null,
        independentUsername: String? = null,
        independentPassword: String? = null
    ): String {
        // 1. Service-specific token from PasswordSafe
        readToken(serviceTokenKey(service))?.let { return it }

        // 2. Independent username/password configured → login with independent or shared template
        if (!independentUsername.isNullOrBlank() && !independentPassword.isNullOrBlank()) {
            val loginConfig = independentLogin ?: loadSharedLoginConfig()
                ?: error("No login template configured. Set llm.auth.login in .ai-test.yaml")
            val token = executeLogin(loginConfig, independentUsername, independentPassword)
            saveToken(serviceTokenKey(service), token)
            return token
        }

        // 4. Saved shared credentials → silent login
        val savedCreds = readCredentials(SSO_CREDENTIALS_KEY)
        if (savedCreds != null) {
            val loginConfig = loadSharedLoginConfig()
                ?: error("No login template configured. Set llm.auth.login in .ai-test.yaml")
            val token = executeLogin(loginConfig, savedCreds.first, savedCreds.second)
            if (token.isNotBlank()) {
                saveToken(serviceTokenKey(service), token)
                return token
            }
        }

        // 5. Prompt user
        return promptAndLogin(service, independentLogin)
    }

    /** Called on 401: clear token, re-login, return new token */
    fun onUnauthorized(
        service: String,
        independentLogin: TemplateRequestConfig? = null,
        independentUsername: String? = null,
        independentPassword: String? = null
    ): String {
        // Clear service-specific token
        clearToken(serviceTokenKey(service))

        // Independent credentials → retry with those
        if (!independentUsername.isNullOrBlank() && !independentPassword.isNullOrBlank()) {
            val loginConfig = independentLogin ?: loadSharedLoginConfig()
                ?: error("No login template configured")
            val token = executeLogin(loginConfig, independentUsername, independentPassword)
            saveToken(serviceTokenKey(service), token)
            return token
        }

        // Try saved shared credentials silently
        val savedCreds = readCredentials(SSO_CREDENTIALS_KEY)
        if (savedCreds != null) {
            val loginConfig = loadSharedLoginConfig()
                ?: error("No login template configured")
            val token = executeLogin(loginConfig, savedCreds.first, savedCreds.second)
            if (token.isNotBlank()) {
                saveToken(serviceTokenKey(service), token)
                return token
            }
        }

        // Prompt user
        return promptAndLogin(service, independentLogin)
    }

    // ---- Private ----

    private var lastLoginError: String? = null

    private fun promptAndLogin(service: String, independentLogin: TemplateRequestConfig?): String {
        val loginConfig = independentLogin ?: loadSharedLoginConfig()
            ?: error("No login template configured. Set llm.auth.login in .ai-test.yaml")

        val credentials = promptCredentials()
        val token = executeLogin(loginConfig, credentials.username, credentials.password)
        if (token.isBlank()) {
            val hint = lastLoginError?.let { " | last error: $it" }.orEmpty()
            error("SSO login returned empty token for $service$hint. Check logs for details.")
        }

        saveToken(serviceTokenKey(service), token)
        if (credentials.remember) {
            saveCredentials(SSO_CREDENTIALS_KEY, credentials.username, credentials.password)
        }
        return token
    }

    private fun promptCredentials(): LoginCredentials {
        val savedCreds = readCredentials(SSO_CREDENTIALS_KEY)
        val prefill = if (savedCreds != null) {
            Credentials(savedCreds.first, savedCreds.second)
        } else null

        lateinit var result: LoginCredentials
        val showDialog = {
            val dialog = LlmLoginDialog(
                project = project,
                initialUsername = prefill?.userName.orEmpty(),
                initialPassword = prefill?.getPasswordAsString().orEmpty(),
                rememberByDefault = prefill != null
            )
            if (!dialog.showAndGet()) error("SSO login cancelled")
            result = dialog.credentials()
            if (result.username.isBlank()) error("SSO login requires a username")
            if (result.password.isBlank()) error("SSO login requires a password")
        }

        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) showDialog()
        else app.invokeAndWait(showDialog, ModalityState.any())

        return result
    }

    private fun executeLogin(config: TemplateRequestConfig, username: String, password: String): String {
        val disableTls = LlmSettingsLoader.load(project).httpDisableTlsVerification
        return try {
            runBlocking {
                TemplateRequestExecutor(
                    HttpClients.shared(disableTlsVerification = disableTls, timeoutSeconds = 60)
                ).execute(
                    config = config,
                    variables = mapOf(
                        "username" to username,
                        "password" to password,
                        "model" to "",
                        "apiKey" to "",
                        "prompt" to "",
                        "promptJson" to "\"\""
                    )
                )
            }.trim().takeIf { it.isNotBlank() }.orEmpty()
        } catch (e: Exception) {
            lastLoginError = "${e.javaClass.simpleName}: ${e.message}"
            ""
        }
    }

    private fun loadSharedLoginConfig(): TemplateRequestConfig? {
        val settings = LlmSettingsLoader.load(project)
        return settings.auth?.login
    }

    // ---- PasswordSafe helpers ----

    private fun readToken(key: String): String? {
        val creds = PasswordSafe.instance.get(CredentialAttributes(key))
        return creds?.getPasswordAsString()?.takeIf { it.isNotBlank() }
    }

    private fun saveToken(key: String, token: String) {
        PasswordSafe.instance.set(CredentialAttributes(key), Credentials(null, token))
    }

    private fun clearToken(key: String) {
        PasswordSafe.instance.set(CredentialAttributes(key), null)
    }

    private fun readCredentials(key: String): Pair<String, String>? {
        val creds = PasswordSafe.instance.get(CredentialAttributes(key)) ?: return null
        val user = creds.userName.orEmpty()
        val pass = creds.getPasswordAsString().orEmpty()
        return if (user.isNotBlank() && pass.isNotBlank()) Pair(user, pass) else null
    }

    private fun saveCredentials(key: String, username: String, password: String) {
        PasswordSafe.instance.set(CredentialAttributes(key), Credentials(username, password))
    }
}
