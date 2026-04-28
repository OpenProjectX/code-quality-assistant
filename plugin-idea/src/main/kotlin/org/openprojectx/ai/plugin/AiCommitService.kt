package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking

class AiCommitService(private val project: Project) {

    fun generate(diff: String, templateOverride: String? = null): String {
        val prompt = buildPrompt(diff, templateOverride)

        return LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
            val provider = LlmProviderFactory.create(settings)
            runBlocking { provider.generateCode(prompt) }.trim()
        }
    }

    private fun buildPrompt(diff: String, templateOverride: String?): String {
        val branchName = resolveCurrentBranchName()
        val template = templateOverride
            ?: PromptProfileResolver.resolve(
                LlmSettingsLoader.loadConfig(project).prompts.profiles.commitMessage,
                AiPromptDefaults.COMMIT_MESSAGE
            )
        val rendered = AiPromptDefaults.render(
            template,
            mapOf(
                "diff" to diff,
                "branchName" to branchName
            )
        )
        return "Current branch: $branchName\n\n$rendered"
    }

    private fun resolveCurrentBranchName(): String {
        return GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName ?: "unknown"
    }
}
