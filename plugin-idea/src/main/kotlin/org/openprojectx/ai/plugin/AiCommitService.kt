package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking

class AiCommitService(private val project: Project) {

    fun generate(diff: String): String {
        val prompt = buildPrompt(diff)

        return LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
            val provider = LlmProviderFactory.create(settings)
            runBlocking { provider.generateCode(prompt) }.trim()
        }
    }

    private fun buildPrompt(diff: String): String {
        return """
            You are an expert Git commit message generator.

            Based on the git diff below, generate a concise and high-quality commit message.

            Requirements:
            - Use Conventional Commits style when appropriate
            - Output only the commit message
            - First line must be <= 72 characters if possible
            - Focus on intent and user-visible/codebase-visible change
            - Do not include markdown fences
            - Do not explain your answer

            Preferred format:
            <type>: <short summary>

            Optional body:
            - bullet points only if really needed

            Git diff:
            $diff
        """.trimIndent()
    }
}
