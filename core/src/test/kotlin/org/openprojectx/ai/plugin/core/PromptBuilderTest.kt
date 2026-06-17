package org.openprojectx.ai.plugin.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    private fun javaRequest(environmentContext: String) = GenerationRequest(
        contractText = "public class Foo { public int bar() { return 1; } }",
        framework = Framework.REST_ASSURED,
        contractType = ContractType.JAVA,
        packageName = "com.example",
        className = "FooTest",
        outputNotes = null,
        environmentContext = environmentContext
    )

    @Test
    fun `injects environment context block into the java prompt`() {
        val env = "## Test Environment (use ONLY what is listed here)\n" +
            "- Java language level: 17\n" +
            "- Test libraries available: JUnit 5, AssertJ"

        val prompt = PromptBuilder.build(javaRequest(env))

        assertTrue(
            prompt.contains("## Test Environment (use ONLY what is listed here)"),
            "prompt should contain the environment heading"
        )
        assertTrue(prompt.contains("Java language level: 17"), "prompt should contain detected language level")
        assertTrue(prompt.contains("JUnit 5, AssertJ"), "prompt should contain detected test libraries")
    }

    @Test
    fun `omits environment block when context is blank`() {
        val prompt = PromptBuilder.build(javaRequest(""))

        assertFalse(prompt.contains("## Test Environment"), "no environment heading when nothing was detected")
    }
}
