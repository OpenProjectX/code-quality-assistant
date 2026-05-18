package org.openprojectx.ai.plugin.llm

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Template
import com.jayway.jsonpath.JsonPath
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
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
            val effectiveRequestHeaders = if (renderedHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                renderedHeaders + ("Content-Type" to ContentType.Application.Json.toString())
            } else {
                renderedHeaders
            }
            val safeRequestHeaders = redactHeaders(effectiveRequestHeaders)
            val safeRequestBody = redactSensitivePayload(renderedBody)
            LlmRuntimeLogger.info(
                "Template request start | method=${config.method.uppercase()} | url=$renderedUrl | headers=$safeRequestHeaders | body=$safeRequestBody"
            )

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
            val responseText = response.bodyAsText()
            val safeResponseHeaders = redactHeaders(response.headers.names().associateWith { name ->
                response.headers.getAll(name).orEmpty().joinToString(",")
            })
            val safeResponseBody = redactSensitivePayload(responseText)
            LlmRuntimeLogger.info(
                "Template response received | url=$renderedUrl | status=${response.status.value} | headers=$safeResponseHeaders | body=$safeResponseBody"
            )
            val responseSummary = responseSummary(
                status = response.status.value,
                headers = safeResponseHeaders,
                body = safeResponseBody
            )
            if (response.status == HttpStatusCode.Unauthorized) {
                LlmRuntimeLogger.error("Template unauthorized | url=$renderedUrl | $responseSummary")
                throw LlmUnauthorizedException("Unauthorized request for template '$renderedUrl'. Received response: $responseSummary")
            }

            val extracted = try {
                JsonPath.read<String>(responseText, config.responsePath)
                    ?: error("Template responsePath '${config.responsePath}' returned null")
            } catch (_: Throwable) {
                throw IllegalStateException(
                    "Template responsePath '${config.responsePath}' was not found in the received response. " +
                        "Received response: $responseSummary"
                )
            }
            LlmRuntimeLogger.info(
                "Template response extracted | path=${config.responsePath} | length=${extracted.length} | preview=${maskSecret(extracted).take(200)}"
            )
            return extracted
        } catch (t: Throwable) {
            LlmRuntimeLogger.error(
                "Template request failed | method=${config.method.uppercase()} | url=${config.url} | responsePath=${config.responsePath} | error=${t.message ?: t::class.java.simpleName}"
            )
            throw t
        }
    }

    private fun render(templateText: String, variables: Map<String, Any>): String {
        val template: Template = handlebars.compileInline(templateText)
        return template.apply(variables)
    }

    private fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        return headers.mapValues { (name, value) ->
            if (name.contains("authorization", ignoreCase = true) ||
                name.contains("token", ignoreCase = true) ||
                name.contains("key", ignoreCase = true) ||
                name.contains("secret", ignoreCase = true) ||
                name.contains("password", ignoreCase = true) ||
                name.contains("cookie", ignoreCase = true)
            ) {
                maskSecret(value)
            } else {
                value
            }
        }
    }

    private fun responseSummary(status: Int, headers: Map<String, String>, body: String): String {
        return "status=$status | headers=$headers | body=$body"
    }

    private fun redactSensitivePayload(text: String): String {
        val sensitiveKeys = listOf("password", "token", "access_token", "id_token", "refresh_token", "apiKey", "api_key", "secret")
        var result = text
        sensitiveKeys.forEach { key ->
            val quotedJsonPattern = Regex("""("${Regex.escape(key)}"\s*:\s*")[^"]*(")""", RegexOption.IGNORE_CASE)
            result = result.replace(quotedJsonPattern) { match ->
                match.groupValues[1] + "***" + match.groupValues[2]
            }
            val formPattern = Regex("""(?i)(^|[&\s])(${Regex.escape(key)}=)[^&\s]+""")
            result = result.replace(formPattern) { match ->
                match.groupValues[1] + match.groupValues[2] + "***"
            }
        }
        return result.take(MAX_LOG_BODY_CHARS).let { truncated ->
            if (result.length > MAX_LOG_BODY_CHARS) "$truncated...<truncated ${result.length - MAX_LOG_BODY_CHARS} chars>" else truncated
        }
    }

    private fun maskSecret(value: String): String {
        if (value.isBlank()) return "<blank>"
        return "***"
    }

    private companion object {
        const val MAX_LOG_BODY_CHARS = 4_000
    }
}
