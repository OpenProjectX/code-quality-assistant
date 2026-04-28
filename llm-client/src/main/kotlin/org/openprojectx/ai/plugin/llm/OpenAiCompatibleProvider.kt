package org.openprojectx.ai.plugin.llm


import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

class OpenAiCompatibleProvider(
    private val http: HttpClient,
    private val settings: LlmSettings
) : LlmProvider {

    override suspend fun generateCode(prompt: String): String {
        try {
            val endpoint = settings.endpoint
                ?: error("llm.endpoint is required for provider='${settings.provider}'")
            val apiKey = settings.apiKey
                ?: error("llm.apiKey or llm.apiKeyEnv is required for provider='${settings.provider}'")
            LlmRuntimeLogger.info("Request start | provider=${settings.provider} | endpoint=$endpoint")

            val req = ChatCompletionsRequest(
                model = settings.model,
                messages = listOf(
                    Message("system", "You generate code only."),
                    Message("user", prompt)
                ),
                temperature = 0.1
            )

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
            val result = resp.choices.firstOrNull()?.message?.content
                ?: error("Empty LLM response")
            LlmRuntimeLogger.info(
                "Response parsed | choices=${resp.choices.size} | contentLength=${result.length} | preview=${result.take(200)}"
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
        val temperature: Double? = null
    )

    @Serializable
    data class Message(val role: String, val content: String)

    @Serializable
    data class ChatCompletionsResponse(
        val choices: List<Choice>
    ) {
        @Serializable
        data class Choice(val message: Message)
    }
}
