package org.openprojectx.ai.plugin.pr

import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openprojectx.ai.plugin.HttpClients
import org.openprojectx.ai.plugin.LlmAuthSessionService
import org.openprojectx.ai.plugin.LlmProviderFactory
import org.openprojectx.ai.plugin.LlmSettingsLoader

class AiPullRequestService(private val project: Project) {

    private val json = Json { ignoreUnknownKeys = true }

    fun createAfterPush(
        remoteUrl: String,
        sourceBranch: String,
        targetBranch: String,
        diff: String,
        providerToken: String
    ): PullRequestResult {
        val repository = GitRemoteParser.parse(remoteUrl)

        val prompt = PullRequestPromptBuilder.build(
            template = LlmSettingsLoader.loadConfig(project).prompts.pullRequest,
            diff = shorten(diff),
            sourceBranch = sourceBranch,
            targetBranch = targetBranch
        )

        val raw = LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
            val llm = LlmProviderFactory.create(settings)
            runBlocking { llm.generateCode(prompt) }.trim()
        }
        val generated = json.decodeFromString<GeneratedPullRequestContent>(raw)

        val provider = GitHostingProviderFactory.create(
            type = repository.provider,
            http = HttpClients.shared(),
            token = providerToken
        )

        return runBlocking {
            provider.createPullRequest(
                PullRequestRequest(
                    repository = repository,
                    sourceBranch = sourceBranch,
                    targetBranch = targetBranch,
                    title = generated.title.trim(),
                    description = generated.description.trim()
                )
            )
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
