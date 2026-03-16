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
        val endpoint = settings.endpoint
            ?: error("llm.endpoint is required for provider='${settings.provider}'")
        val apiKey = settings.apiKey
            ?: error("llm.apiKey or llm.apiKeyEnv is required for provider='${settings.provider}'")

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

        if (response.status == HttpStatusCode.Unauthorized) {
            throw LlmUnauthorizedException("Unauthorized LLM request to $endpoint")
        }

        val resp: ChatCompletionsResponse = response.body()

        return resp.choices.firstOrNull()?.message?.content
            ?: error("Empty LLM response")
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
