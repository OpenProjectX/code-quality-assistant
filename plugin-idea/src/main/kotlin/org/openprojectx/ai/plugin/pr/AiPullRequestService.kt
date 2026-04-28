package org.openprojectx.ai.plugin.pr

import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openprojectx.ai.plugin.HttpClients
import org.openprojectx.ai.plugin.GitCredentialHelper
import org.openprojectx.ai.plugin.LlmAuthSessionService
import org.openprojectx.ai.plugin.LlmProviderFactory
import org.openprojectx.ai.plugin.LlmSettingsLoader
import org.openprojectx.ai.plugin.ButtonUsageReportService

class AiPullRequestService(private val project: Project) {

    private val json = Json { ignoreUnknownKeys = true }

    fun createAfterPush(
        remoteUrl: String,
        sourceBranch: String,
        targetBranch: String,
        diff: String,
        summaryComment: String? = null
    ): PullRequestResult {
        val repository = GitRemoteParser.parse(remoteUrl)
        val settings = LlmSettingsLoader.loadSettingsModel(project)
        val credential = GitCredentialHelper.resolve(remoteUrl)
        val auth = PullRequestAuth(
            token = settings.bitbucketPromptRepoToken.takeIf { it.isNotBlank() },
            username = credential?.username,
            password = credential?.password
        )

        val prompt = PullRequestPromptBuilder.build(
            template = LlmSettingsLoader.loadConfig(project).prompts.pullRequest,
            diff = shorten(diff),
            sourceBranch = sourceBranch,
            targetBranch = targetBranch
        )
        ButtonUsageReportService.getInstance(project).recordPromptUsage("pull.request", "default")

        val raw = LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
            val llm = LlmProviderFactory.create(settings)
            runBlocking { llm.generateCode(prompt) }.trim()
        }
        val generated = json.decodeFromString<GeneratedPullRequestContent>(raw)

        val provider = GitHostingProviderFactory.create(
            type = repository.provider,
            http = HttpClients.shared(),
            auth = auth
        )

        return runBlocking {
            val result = provider.createPullRequest(
                PullRequestRequest(
                    repository = repository,
                    sourceBranch = sourceBranch,
                    targetBranch = targetBranch,
                    title = generated.title.trim(),
                    description = generated.description.trim()
                )
            )
            if (!summaryComment.isNullOrBlank() && !result.id.isNullOrBlank()) {
                provider.addComment(
                    repository = repository,
                    pullRequestId = result.id,
                    text = summaryComment.trim()
                )
            }
            result
        }
    }

    private fun shorten(diff: String, maxChars: Int = 25_000): String {
        return if (diff.length <= maxChars) diff else diff.take(maxChars)
    }

    @Serializable
    data class GeneratedPullRequestContent(
        val title: String,
        val description: String
    )
}
