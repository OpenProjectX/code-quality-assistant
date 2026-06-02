package org.openprojectx.ai.plugin

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

suspend inline fun <reified T> HttpClient.safeGet(
    url: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T {
    val response = get(url, block)
    if (!response.status.isSuccess()) {
        val errorBody = runCatching { response.bodyAsText() }.getOrDefault("(unable to read error body)")
        throw RuntimeException("SonarQube API returned HTTP ${response.status.value} for $url: $errorBody")
    }
    return response.body()
}

object HttpClients {
    fun shared(disableTlsVerification: Boolean = false, timeoutSeconds: Long = 60): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    if (disableTlsVerification) {
                        trustAllCerts(this)
                    }
                }
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
            install(HttpTimeout) {
                connectTimeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds)
                socketTimeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds)
                requestTimeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds)
            }
        }
    }

    fun trustAllCerts(builder: okhttp3.OkHttpClient.Builder) {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }
    }

    fun logCurl(method: String, url: String, headers: Map<String, String>, body: String = "") {
        val safeHeaders = headers.mapValues { (name, value) -> redactHeaderValue(name, value) }
        val safeBody = truncate(redactSensitivePayload(body))
        val headerArgs = safeHeaders.entries.joinToString(" ") { (name, value) ->
            "-H ${shellQuote("$name: $value")}"
        }
        val bodyArg = if (safeBody.isNotBlank()) " --data ${shellQuote(safeBody)}" else ""
        val command = "curl -X ${method.uppercase()} ${shellQuote(redactSensitiveUrl(url))}" +
            (if (headerArgs.isNotBlank()) " $headerArgs" else "") + bodyArg
        RuntimeLogStore.append("INFO | API curl | $command")
    }

    private fun redactHeaderValue(name: String, value: String): String =
        if (isSensitiveName(name)) "***" else value

    private fun redactSensitiveUrl(url: String): String {
        var result = url
        SENSITIVE_KEYS.forEach { key ->
            val queryPattern = Regex("(?i)([?&]${Regex.escape(key)}=)[^&#]*")
            result = result.replace(queryPattern) { "${it.groupValues[1]}***" }
        }
        return result
    }

    private fun redactSensitivePayload(text: String): String {
        if (text.isBlank()) return text
        var result = text
        SENSITIVE_KEYS.forEach { key ->
            val quotedJsonPattern = Regex("""("${Regex.escape(key)}"\s*:\s*")[^"]*(")""", RegexOption.IGNORE_CASE)
            result = result.replace(quotedJsonPattern) { "${it.groupValues[1]}***${it.groupValues[2]}" }
            val formPattern = Regex("""(?i)(^|[&\s])(${Regex.escape(key)}=)[^&\s]+""")
            result = result.replace(formPattern) { "${it.groupValues[1]}${it.groupValues[2]}***" }
        }
        return result
    }

    private fun truncate(value: String): String =
        if (value.length <= MAX_LOG_BODY_CHARS) value
        else value.take(MAX_LOG_BODY_CHARS) + "...<truncated ${value.length - MAX_LOG_BODY_CHARS} chars>"

    private fun isSensitiveName(name: String): Boolean = SENSITIVE_KEYS.any { name.contains(it, true) }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private val SENSITIVE_KEYS = listOf(
        "authorization", "password", "token", "access_token", "id_token", "refresh_token",
        "apiKey", "api_key", "secret", "cookie"
    )
    private const val MAX_LOG_BODY_CHARS = 4_000

}