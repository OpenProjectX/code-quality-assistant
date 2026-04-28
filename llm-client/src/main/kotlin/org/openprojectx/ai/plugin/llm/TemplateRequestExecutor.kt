package org.openprojectx.ai.plugin.llm

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Template
import com.jayway.jsonpath.JsonPath
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

class TemplateRequestExecutor(
    private val http: HttpClient
) {
    private val handlebars = Handlebars()

    suspend fun execute(config: TemplateRequestConfig, variables: Map<String, Any>): String {
        try {
            val renderedUrl = render(config.url, variables)
            val renderedHeaders = config.headers.mapValues { (_, value) -> render(value, variables) }
            val renderedBody = render(config.body, variables)
            LlmRuntimeLogger.info("Template request start | method=${config.method.uppercase()} | url=$renderedUrl")

            val response = http.request {
                url(renderedUrl)
                method = HttpMethod.parse(config.method.uppercase())

                renderedHeaders.forEach { (name, value) ->
                    header(name, value)
                }

                if (renderedHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    contentType(ContentType.Application.Json)
                }

                setBody(renderedBody)
            }
            LlmRuntimeLogger.info("Template response received | url=$renderedUrl | status=${response.status.value}")

            val responseText = readBodyOrThrow(response, config)
            LlmRuntimeLogger.info(
                "Template response body received | url=$renderedUrl | length=${responseText.length} | preview=${responseText.take(200)}"
            )

            val extracted = JsonPath.read<String>(responseText, config.responsePath)
                ?: error("Template responsePath '${config.responsePath}' returned null")
            LlmRuntimeLogger.info(
                "Template response extracted | path=${config.responsePath} | length=${extracted.length} | preview=${extracted.take(200)}"
            )
            return extracted
        } catch (t: Throwable) {
            LlmRuntimeLogger.error(
                "Template request failed | method=${config.method.uppercase()} | url=${config.url} | error=${t.message ?: t::class.java.simpleName}"
            )
            throw t
        }
    }

    private suspend fun readBodyOrThrow(response: HttpResponse, config: TemplateRequestConfig): String {
        val responseText = response.bodyAsText()
        if (response.status == HttpStatusCode.Unauthorized) {
            LlmRuntimeLogger.error("Template unauthorized | url=${config.url} | status=${response.status.value}")
            throw LlmUnauthorizedException("Unauthorized request for template '${config.url}'")
        }
        return responseText
    }

    private fun render(templateText: String, variables: Map<String, Any>): String {
        val template: Template = handlebars.compileInline(templateText)
        return template.apply(variables)
    }
}
