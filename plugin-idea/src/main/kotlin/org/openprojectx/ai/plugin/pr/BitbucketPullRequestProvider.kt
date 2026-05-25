package org.openprojectx.ai.plugin.pr

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

class BitbucketPullRequestProvider(
    private val http: HttpClient,
    private val auth: PullRequestAuth
) : GitHostingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createPullRequest(request: PullRequestRequest): PullRequestResult {
        val repo = request.repository
        val apiUrl =
            "${repo.apiBaseUrl.trimEnd('/')}/rest/api/1.0/projects/${repo.projectKey}/repos/${repo.repoSlug}/pull-requests"

        val payload = CreateBitbucketPrRequest(
            title = request.title,
            description = request.description,
            state = "OPEN",
            open = true,
            closed = false,
            locked = false,
            fromRef = Ref(
                id = "refs/heads/${request.sourceBranch}",
                repository = BitbucketRepoRef(project = BitbucketProjectRef(repo.projectKey), slug = repo.repoSlug)
            ),
            toRef = Ref(
                id = "refs/heads/${request.targetBranch}",
                repository = BitbucketRepoRef(project = BitbucketProjectRef(repo.projectKey), slug = repo.repoSlug)
            )
        )

        val response = http.post(apiUrl) {
            applyAuthorizationHeader()
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw BitbucketApiException(bitbucketErrorMessage(responseText, response.status.value))
        }
        val prResponse = json.decodeFromString<CreateBitbucketPrResponse>(responseText)

        return PullRequestResult(
            url = prResponse.links.self.firstOrNull()?.href.orEmpty(),
            id = prResponse.id?.toString()
        )
    }

    override suspend fun addComment(repository: RepositoryRef, pullRequestId: String, text: String) {
        val apiUrl =
            "${repository.apiBaseUrl.trimEnd('/')}/rest/api/1.0/projects/${repository.projectKey}/repos/${repository.repoSlug}/pull-requests/$pullRequestId/comments"

        val response = http.post(apiUrl) {
            applyAuthorizationHeader()
            contentType(ContentType.Application.Json)
            setBody(CreateBitbucketCommentRequest(text = text))
        }
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw BitbucketApiException(bitbucketErrorMessage(responseText, response.status.value))
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuthorizationHeader() {
        when {
            !auth.token.isNullOrBlank() -> {
                header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            }
            !auth.username.isNullOrBlank() && !auth.password.isNullOrBlank() -> {
                val raw = "${auth.username}:${auth.password}"
                val basic = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
                header(HttpHeaders.Authorization, "Basic $basic")
            }
            else -> {
                error("Bitbucket authentication is required. Configure a token or ensure Git credentials are available for this repository.")
            }
        }
    }

    private fun bitbucketErrorMessage(responseText: String, statusCode: Int): String {
        val parsed = runCatching { json.decodeFromString<BitbucketErrorResponse>(responseText) }.getOrNull()
        val message = parsed?.errors?.firstOrNull()?.message?.takeIf { it.isNotBlank() }
        return message ?: "Bitbucket API request failed with HTTP $statusCode: $responseText"
    }

    @Serializable
    data class CreateBitbucketPrRequest(
        val title: String,
        val description: String,
        val state: String,
        val open: Boolean,
        val closed: Boolean,
        val locked: Boolean,
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
        val project: BitbucketProjectRef,
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
    data class BitbucketErrorResponse(
        val errors: List<BitbucketError> = emptyList()
    )

    @Serializable
    data class BitbucketError(
        val context: String? = null,
        val message: String? = null,
        val exceptionName: String? = null
    )

    @Serializable
    data class Links(
        val self: List<Link> = emptyList()
    )

    @Serializable
    data class Link(
        val href: String
    )

    class BitbucketApiException(message: String) : RuntimeException(message)
}
