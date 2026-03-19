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
        val renderedUrl = render(config.url, variables)
        val renderedHeaders = config.headers.mapValues { (_, value) -> render(value, variables) }
        val renderedBody = render(config.body, variables)

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

        val responseText = readBodyOrThrow(response, config)

        return JsonPath.read<String>(responseText, config.responsePath)
            ?: error("Template responsePath '${config.responsePath}' returned null")
    }

    private suspend fun readBodyOrThrow(response: HttpResponse, config: TemplateRequestConfig): String {
        val responseText = response.bodyAsText()
        if (response.status == HttpStatusCode.Unauthorized) {
            throw LlmUnauthorizedException("Unauthorized request for template '${config.url}'")
        }
        return responseText
    }

    private fun render(templateText: String, variables: Map<String, Any>): String {
        val template: Template = handlebars.compileInline(templateText)
        return template.apply(variables)
    }
}
