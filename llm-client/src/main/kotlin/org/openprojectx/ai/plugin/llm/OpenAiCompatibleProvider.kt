package org.openprojectx.ai.plugin.llm


import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenAiCompatibleProvider(
    private val http: HttpClient,
    private val settings: LlmSettings
) : LlmProvider {

    override suspend fun generateCode(prompt: String): String {
        return generateCode(
            listOf(
                Message("system", "You are a helpful coding assistant. Respond concisely."),
                Message("user", prompt)
            )
        )
    }

    override suspend fun generateCode(messages: List<Message>): String {
        try {
            val endpoint = settings.endpoint
                ?: error("llm.endpoint is required for provider='${settings.provider}'")
            val apiKey = settings.apiKey
                ?: error("llm.apiKey or llm.apiKeyEnv is required for provider='${settings.provider}'")
            LlmRuntimeLogger.info("Request start | provider=${settings.provider} | endpoint=$endpoint")

            // Auto-prepend system message if caller didn't include one
            val hasSystem = messages.any { it.role == "system" }
            val finalMessages = if (hasSystem) messages
                else listOf(Message("system", "You are a helpful coding assistant. Respond concisely.")) + messages

            val req = ChatCompletionsRequest(
                model = settings.model,
                messages = finalMessages,
                temperature = 0.1,
                max_tokens = settings.maxTokens.takeIf { it > 0 }
            )

            val curlCmd = buildCurlCommand(endpoint, apiKey, req)
            LlmRuntimeLogger.info("curl | $curlCmd")

            val response = http.post(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(req)
            }
            LlmRuntimeLogger.info("Response received | endpoint=$endpoint | status=${response.status.value}")

            if (response.status == HttpStatusCode.Unauthorized) {
                LlmRuntimeLogger.error("Unauthorized response | endpoint=$endpoint")
                throw LlmUnauthorizedException("Unauthorized LLM request to $endpoint")
            }

            val resp: ChatCompletionsResponse = response.body()
            val choice = resp.choices.firstOrNull() ?: error("Empty LLM response")
            val result = choice.message.content
            if (choice.finish_reason == "length") {
                LlmRuntimeLogger.warn(
                    "Response truncated by token limit (finish_reason=length) | contentLength=${result.length}"
                )
            }
            LlmRuntimeLogger.info(
                "Response parsed | choices=${resp.choices.size} | contentLength=${result.length} | finish_reason=${choice.finish_reason} | preview=${result.take(200)}"
            )

            return result
        } catch (t: Throwable) {
            LlmRuntimeLogger.error("Request failed | provider=${settings.provider} | error=${t.message ?: t::class.java.simpleName}")
            throw t
        }
    }

    @Serializable
    data class ChatCompletionsRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double? = null,
        val max_tokens: Int? = null
    )

    @Serializable
    data class Message(val role: String, val content: String)

    @Serializable
    data class ChatCompletionsResponse(
        val choices: List<Choice>
    ) {
        @Serializable
        data class Choice(val message: Message, val finish_reason: String? = null)
    }

    companion object {
        private val curlJson = Json { prettyPrint = false }

        private fun buildCurlCommand(endpoint: String, apiKey: String, req: ChatCompletionsRequest): String {
            val body = curlJson.encodeToString(ChatCompletionsRequest.serializer(), req)
                .replace("'", "'\"'\"'")
            return "curl -X POST '$endpoint' -H 'Authorization: Bearer ***' -H 'Content-Type: application/json' --data '$body'"
        }
    }
}
