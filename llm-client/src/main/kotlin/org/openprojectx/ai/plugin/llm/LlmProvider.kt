package org.openprojectx.ai.plugin.llm

interface LlmProvider {
    suspend fun generateCode(prompt: String): String

    suspend fun generateCode(messages: List<OpenAiCompatibleProvider.Message>): String {
        // Default: flatten structured messages into a single prompt for non-OpenAI providers
        val prompt = messages.joinToString("\n") { "[${it.role}]: ${it.content}" }
        return generateCode(prompt)
    }
}