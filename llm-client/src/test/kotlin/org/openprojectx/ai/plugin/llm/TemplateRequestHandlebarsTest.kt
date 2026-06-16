package org.openprojectx.ai.plugin.llm

import com.github.jknack.handlebars.EscapingStrategy
import com.github.jknack.handlebars.Handlebars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TemplateRequestHandlebarsTest {

    private val bodyTemplate = """{"username": "{{username}}", "password": "{{password}}"}"""

    @Test
    fun `password with double-quote is corrupted by default HTML escaping`() {
        val handlebars = Handlebars()
        val template = handlebars.compileInline(bodyTemplate)
        val rendered = template.apply(mapOf("username" to "user1", "password" to """pass"123"""))

        assertEquals(
            """{"username": "user1", "password": "pass&quot;123"}""",
            rendered
        )
    }

    @Test
    fun `password with ampersand is corrupted by default HTML escaping`() {
        val handlebars = Handlebars()
        val template = handlebars.compileInline(bodyTemplate)
        val rendered = template.apply(mapOf("username" to "user1", "password" to "pass&123"))

        assertEquals(
            """{"username": "user1", "password": "pass&amp;123"}""",
            rendered
        )
    }

    @Test
    fun `password with special chars is preserved with no-op escaping strategy`() {
        val handlebars = Handlebars().with(EscapingStrategy { it })
        val template = handlebars.compileInline(bodyTemplate)

        val passwordsToTest = listOf(
            """pass"123""",
            "pass&123",
            "pass<456",
            "pass>456",
            "pass'456",
            "normal_password",
            "P@ssw0rd!@#$%^"
        )

        for (password in passwordsToTest) {
            val rendered = template.apply(mapOf("username" to "user1", "password" to password))
            assertEquals(
                """{"username": "user1", "password": "$password"}""",
                rendered,
                "Password '$password' should be preserved verbatim in rendered output"
            )
        }
    }
}
