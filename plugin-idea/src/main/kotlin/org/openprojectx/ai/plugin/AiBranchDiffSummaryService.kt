package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking

class AiBranchDiffSummaryService(private val project: Project) {

    fun generate(sourceBranch: String, targetBranch: String, diff: String, templateOverride: String? = null): String {
        val prompt = buildPrompt(sourceBranch, targetBranch, diff, templateOverride)

        return LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
            val provider = LlmProviderFactory.create(settings)
            runBlocking { provider.generateCode(prompt) }.trim()
        }
    }

    private fun buildPrompt(sourceBranch: String, targetBranch: String, diff: String, templateOverride: String?): String {
        val template = templateOverride
            ?: PromptProfileResolver.resolve(
                LlmSettingsLoader.loadConfig(project).prompts.profiles.branchDiffSummary,
                AiPromptDefaults.BRANCH_DIFF_SUMMARY
            )
        return AiPromptDefaults.render(
            template,
            mapOf(
                "sourceBranch" to sourceBranch,
                "targetBranch" to targetBranch,
                "diff" to shorten(diff)
            )
        )
    }

    private fun shorten(diff: String, maxChars: Int = 25_000): String {
        return if (diff.length <= maxChars) diff else diff.take(maxChars)
    }
}
