package org.openprojectx.ai.plugin.pr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64

class BitbucketPullRequestProvider(
    private val http: HttpClient,
    private val auth: PullRequestAuth
) : GitHostingProvider {

    override suspend fun createPullRequest(request: PullRequestRequest): PullRequestResult {
        val repo = request.repository
        val apiUrl =
            "https://${repo.host}/rest/api/1.0/projects/${repo.projectKey}/repos/${repo.repoSlug}/pull-requests"

        val payload = CreateBitbucketPrRequest(
            title = request.title,
            description = request.description,
            state = "OPEN",
            open = true,
            closed = false,
            fromRef = Ref(
                id = "refs/heads/${request.sourceBranch}",
                repository = BitbucketRepoRef(bitbucketProjectRef = BitbucketProjectRef(repo.projectKey), slug = repo.repoSlug)
            ),
            toRef = Ref(
                id = "refs/heads/${request.targetBranch}",
                repository = BitbucketRepoRef(bitbucketProjectRef = BitbucketProjectRef(repo.projectKey), slug = repo.repoSlug)
            )
        )

        val response: CreateBitbucketPrResponse = http.post(apiUrl) {
            applyAuthorizationHeader()
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body()

        return PullRequestResult(
            url = response.links.self.firstOrNull()?.href.orEmpty(),
            id = response.id?.toString()
        )
    }

    override suspend fun addComment(repository: RepositoryRef, pullRequestId: String, text: String) {
        val apiUrl =
            "https://${repository.host}/rest/api/1.0/projects/${repository.projectKey}/repos/${repository.repoSlug}/pull-requests/$pullRequestId/comments"

        http.post(apiUrl) {
            applyAuthorizationHeader()
            contentType(ContentType.Application.Json)
            setBody(CreateBitbucketCommentRequest(text = text))
        }.body<CreateBitbucketCommentResponse>()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuthorizationHeader() {
        when {
            !auth.username.isNullOrBlank() && !auth.password.isNullOrBlank() -> {
                val raw = "${auth.username}:${auth.password}"
                val basic = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
                header(HttpHeaders.Authorization, "Basic $basic")
            }
            !auth.token.isNullOrBlank() -> {
                header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            }
            else -> {
                error("Bitbucket authentication is required. Configure a token or ensure Git credentials are available for this repository.")
            }
        }
    }

    @Serializable
    data class CreateBitbucketPrRequest(
        val title: String,
        val description: String,
        val state: String,
        val open: Boolean,
        val closed: Boolean,
        @SerialName("fromRef") val fromRef: Ref,
        @SerialName("toRef") val toRef: Ref
    )

    @Serializable
    data class Ref(
        val id: String,
        val repository: BitbucketRepoRef
    )

    @Serializable
    data class BitbucketRepoRef(
        val bitbucketProjectRef: BitbucketProjectRef,
        val slug: String
    )

    @Serializable
    data class BitbucketProjectRef(
        val key: String
    )

    @Serializable
    data class CreateBitbucketPrResponse(
        val id: Long? = null,
        val links: Links
    )

    @Serializable
    data class CreateBitbucketCommentRequest(
        val text: String
    )

    @Serializable
    data class CreateBitbucketCommentResponse(
        val id: Long? = null
    )

    @Serializable
    data class Links(
        val self: List<Link> = emptyList()
    )

    @Serializable
    data class Link(
        val href: String
    )
}
