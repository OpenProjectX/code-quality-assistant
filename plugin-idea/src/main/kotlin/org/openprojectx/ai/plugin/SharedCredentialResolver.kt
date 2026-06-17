package org.openprojectx.ai.plugin

/**
 * Resolves the effective username/password for a service that can fall back to the shared
 * credential entered in the basic settings view.
 *
 * Tokens/PATs are deliberately NOT handled here: a token is service-specific and must never be
 * shared across services. Callers apply token auth first (when the service has its own token) and
 * only use this resolver for the basic-auth (username/password) path.
 */
object SharedCredentialResolver {

    data class UsernamePassword(val username: String, val password: String)

    /**
     * Returns the service's own username/password when both are non-blank; otherwise the shared
     * pair when it is complete; otherwise the (blank) service values.
     */
    fun resolveUsernamePassword(
        serviceUsername: String,
        servicePassword: String,
        sharedUsername: String,
        sharedPassword: String
    ): UsernamePassword {
        if (serviceUsername.isNotBlank() && servicePassword.isNotBlank()) {
            return UsernamePassword(serviceUsername, servicePassword)
        }
        if (sharedUsername.isNotBlank() && sharedPassword.isNotBlank()) {
            return UsernamePassword(sharedUsername, sharedPassword)
        }
        return UsernamePassword(serviceUsername, servicePassword)
    }
}
