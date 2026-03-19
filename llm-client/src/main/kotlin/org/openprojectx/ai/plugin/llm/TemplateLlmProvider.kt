package org.openprojectx.ai.plugin.llm

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

class TemplateLlmProvider(
    private val http: HttpClient,
    private val settings: LlmSettings
) : LlmProvider {

    private val json = Json
    private val executor = TemplateRequestExecutor(http)

    override suspend fun generateCode(prompt: String): String {
        val config = settings.template
            ?: error("Template config is required for provider='template'")

        val variables = mapOf(
            "model" to settings.model,
            "apiKey" to (settings.apiKey ?: ""),
            "prompt" to prompt,
            "promptJson" to json.encodeToString(String.serializer(), prompt)
        )

        return try {
            executor.execute(config, variables)
        } catch (e: PathNotFoundException) {
            error("LLM responsePath '${config.responsePath}' did not match response: ${e.message}")
        }
    }
}
