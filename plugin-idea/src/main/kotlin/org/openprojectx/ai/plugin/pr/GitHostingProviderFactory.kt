package org.openprojectx.ai.plugin.pr

import io.ktor.client.HttpClient

object GitHostingProviderFactory {

    fun create(
        type: GitHostingProviderType,
        http: HttpClient,
        auth: PullRequestAuth
    ): GitHostingProvider {
        return when (type) {
            GitHostingProviderType.BITBUCKET -> BitbucketPullRequestProvider(http, auth)
            GitHostingProviderType.GITHUB -> error("GitHub pull request creation is not implemented yet")
            GitHostingProviderType.GITLAB -> error("GitLab merge request creation is not implemented yet")
        }
    }
}
