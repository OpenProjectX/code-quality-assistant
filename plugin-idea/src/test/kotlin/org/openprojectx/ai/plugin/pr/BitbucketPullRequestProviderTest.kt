package org.openprojectx.ai.plugin.pr

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitbucketPullRequestProviderTest {

    @Test
    fun `creates pull request using bitbucket server api contract and bearer auth`() = runBlocking {
        var capturedUrl = ""
        var capturedMethod = HttpMethod.Get
        var capturedAuthorization = ""
        var capturedBody = ""
        val client = testClient { request ->
            capturedUrl = request.url.toString()
            capturedMethod = request.method
            capturedAuthorization = request.headers[HttpHeaders.Authorization].orEmpty()
            capturedBody = request.body.readText()
            respond(
                content = """
                    {
                      "id": 42,
                      "links": {
                        "self": [
                          { "href": "https://git.example.com/projects/PROJ/repos/my-repo/pull-requests/42" }
                        ]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val provider = BitbucketPullRequestProvider(client, PullRequestAuth(token = "abc123"))

        val result = provider.createPullRequest(
            PullRequestRequest(
                repository = repository(),
                sourceBranch = "feature/test",
                targetBranch = "main",
                title = "Add thing",
                description = "Implementation details"
            )
        )

        assertEquals("https://git.example.com/bitbucket/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests", capturedUrl)
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("Bearer abc123", capturedAuthorization)
        assertEquals("https://git.example.com/projects/PROJ/repos/my-repo/pull-requests/42", result.url)
        assertEquals("42", result.id)

        val payload = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("Add thing", payload["title"]?.jsonPrimitive?.content)
        assertEquals("Implementation details", payload["description"]?.jsonPrimitive?.content)
        assertEquals("OPEN", payload["state"]?.jsonPrimitive?.content)
        assertEquals(true, payload["open"]?.jsonPrimitive?.boolean)
        assertEquals(false, payload["closed"]?.jsonPrimitive?.boolean)
        assertEquals(false, payload["locked"]?.jsonPrimitive?.boolean)

        val fromRef = payload.getValue("fromRef").jsonObject
        assertEquals("refs/heads/feature/test", fromRef["id"]?.jsonPrimitive?.content)
        val fromRepo = fromRef.getValue("repository").jsonObject
        assertEquals("my-repo", fromRepo["slug"]?.jsonPrimitive?.content)
        assertEquals("PROJ", fromRepo.getValue("project").jsonObject["key"]?.jsonPrimitive?.content)
        assertTrue("bitbucketProjectRef" !in fromRepo)

        val toRef = payload.getValue("toRef").jsonObject
        assertEquals("refs/heads/main", toRef["id"]?.jsonPrimitive?.content)
        assertEquals("PROJ", toRef.getValue("repository").jsonObject.getValue("project").jsonObject["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `uses basic auth when token is absent`() = runBlocking {
        var capturedAuthorization = ""
        val client = testClient { request ->
            capturedAuthorization = request.headers[HttpHeaders.Authorization].orEmpty()
            respond(
                content = """{"id":7,"links":{"self":[{"href":"https://git.example.com/pr/7"}]}}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val provider = BitbucketPullRequestProvider(
            client,
            PullRequestAuth(username = "alice", password = "secret")
        )

        provider.createPullRequest(
            PullRequestRequest(
                repository = repository(),
                sourceBranch = "feature/test",
                targetBranch = "main",
                title = "Title",
                description = "Description"
            )
        )

        assertEquals("Basic YWxpY2U6c2VjcmV0", capturedAuthorization)
    }

    @Test
    fun `raises readable bitbucket error message`() = runBlocking {
        val client = testClient {
            respond(
                content = """{"errors":[{"context":null,"message":"A pull request already exists","exceptionName":"DuplicatePullRequestException"}]}""",
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val provider = BitbucketPullRequestProvider(client, PullRequestAuth(token = "abc123"))

        val error = assertFailsWith<BitbucketPullRequestProvider.BitbucketApiException> {
            provider.createPullRequest(
                PullRequestRequest(
                    repository = repository(),
                    sourceBranch = "feature/test",
                    targetBranch = "main",
                    title = "Title",
                    description = "Description"
                )
            )
        }

        assertEquals("A pull request already exists", error.message)
    }

    private fun repository(): RepositoryRef {
        return RepositoryRef(
            provider = GitHostingProviderType.BITBUCKET,
            host = "git.example.com",
            projectKey = "PROJ",
            repoSlug = "my-repo",
            apiBaseUrl = "https://git.example.com/bitbucket"
        )
    }

    private fun testClient(handler: MockRequestHandler): HttpClient {
        return HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    private fun OutgoingContent.readText(): String {
        return when (this) {
            is TextContent -> text
            is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
            else -> error("Unsupported request body type in test: ${javaClass.name}")
        }
    }
}
