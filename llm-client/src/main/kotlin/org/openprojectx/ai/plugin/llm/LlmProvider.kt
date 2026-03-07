package org.openprojectx.ai.plugin.llm

interface LlmProvider {
    suspend fun generateCode(prompt: String): String
}