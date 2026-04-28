package org.openprojectx.ai.plugin.pr

data class PullRequestRequest(
    val repository: RepositoryRef,
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val description: String
)

data class PullRequestResult(
    val url: String,
    val id: String? = null
)

data class RepositoryRef(
    val provider: GitHostingProviderType,
    val host: String,
    val projectKey: String,
    val repoSlug: String
)

enum class GitHostingProviderType {
    BITBUCKET,
    GITHUB,
    GITLAB
}

interface GitHostingProvider {
    suspend fun createPullRequest(request: PullRequestRequest): PullRequestResult
    suspend fun addComment(repository: RepositoryRef, pullRequestId: String, text: String) {}
}

data class PullRequestConfig(
    val enabled: Boolean = false,
    val createAfterPush: Boolean = false,
    val token: String? = null,
    val targetBranch: String = "main"
)

data class PullRequestUiOptions(
    val createAfterPush: Boolean,
    val targetBranch: String
)
