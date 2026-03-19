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
        val template = LlmSettingsLoader.loadConfig(project).prompts.commitMessage
        return AiPromptDefaults.render(template, mapOf("diff" to diff))
    }
}
