package org.openprojectx.ai.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import com.intellij.openapi.diagnostic.Logger as IdeaLogger
import kotlinx.serialization.json.Json
import org.openprojectx.ai.plugin.llm.LlmProvider
import org.openprojectx.ai.plugin.llm.LlmRuntimeLogger
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.openprojectx.ai.plugin.llm.OpenAiCompatibleProvider
import org.openprojectx.ai.plugin.llm.TemplateLlmProvider
import java.util.concurrent.TimeUnit

object LlmProviderFactory {
    private val ideaLog = IdeaLogger.getInstance(LlmProviderFactory::class.java)

    fun create(settings: LlmSettings): LlmProvider {
        LlmRuntimeLogger.sink = { message ->
            ideaLog.info(message)
            RuntimeLogStore.append(message)
        }

        val http = HttpClient(OkHttp) {
            engine {
                config {
                    if (settings.httpDisableTlsVerification) {
                        HttpClients.trustAllCerts(this)
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
                connectTimeoutMillis = TimeUnit.SECONDS.toMillis(settings.timeoutSeconds)
                socketTimeoutMillis = TimeUnit.SECONDS.toMillis(settings.timeoutSeconds)
//                requestTimeoutMillis = TimeUnit.SECONDS.toMillis(settings.timeoutSeconds)
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        LlmRuntimeLogger.info("HTTP | $message")
                    }
                }
                level = LogLevel.ALL     // headers + body
            }
        }

        return when (settings.provider.trim().lowercase()) {
            "openai-compatible" -> OpenAiCompatibleProvider(http, settings)
            "template" -> TemplateLlmProvider(http, settings)
            else -> if (settings.template != null) TemplateLlmProvider(http, settings) else OpenAiCompatibleProvider(http, settings)
        }
    }
}
