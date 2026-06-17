package org.openprojectx.ai.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCredentialResolverTest {

    private val shared = SharedCredentialResolver.UsernamePassword("shared-user", "shared-pass")

    @Test
    fun `uses service credentials when both username and password are set`() {
        val result = SharedCredentialResolver.resolveUsernamePassword(
            serviceUsername = "svc-user",
            servicePassword = "svc-pass",
            sharedUsername = shared.username,
            sharedPassword = shared.password
        )
        assertEquals(SharedCredentialResolver.UsernamePassword("svc-user", "svc-pass"), result)
    }

    @Test
    fun `falls back to shared when service credentials are blank`() {
        val result = SharedCredentialResolver.resolveUsernamePassword(
            serviceUsername = "",
            servicePassword = "",
            sharedUsername = shared.username,
            sharedPassword = shared.password
        )
        assertEquals(shared, result)
    }

    @Test
    fun `falls back to shared when service has username but no password`() {
        val result = SharedCredentialResolver.resolveUsernamePassword(
            serviceUsername = "svc-user",
            servicePassword = "",
            sharedUsername = shared.username,
            sharedPassword = shared.password
        )
        assertEquals(shared, result)
    }

    @Test
    fun `returns blanks when neither service nor shared credentials are complete`() {
        val result = SharedCredentialResolver.resolveUsernamePassword(
            serviceUsername = "",
            servicePassword = "",
            sharedUsername = "shared-user",
            sharedPassword = ""
        )
        assertEquals(SharedCredentialResolver.UsernamePassword("", ""), result)
    }
}
