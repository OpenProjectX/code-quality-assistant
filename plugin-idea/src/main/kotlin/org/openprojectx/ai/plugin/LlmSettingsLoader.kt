package org.openprojectx.ai.plugin


import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.yaml.snakeyaml.Yaml
import java.io.File

object LlmSettingsLoader {

    private val configNames = listOf(".ai-test.yml", ".ai-test.yaml")

    fun load(project: Project): LlmSettings {
        val basePath = project.basePath ?: error("Project basePath is null")
        val configFile = findConfigFile(basePath)
            ?: error("AI TestGen config not found. Expected one of: ${configNames.joinToString()} at project root.")

        val text = configFile.readText(Charsets.UTF_8)

        val yaml = Yaml()
        val root = yaml.load<Any?>(text) as? Map<*, *>
            ?: error("Invalid YAML: root is not a map")

        val llm = (root["llm"] as? Map<*, *>) ?: error("Invalid YAML: missing top-level 'llm' object")

        val provider = (llm["provider"] as? String)?.trim().orEmpty().ifEmpty { "openai-compatible" }
        if (provider != "openai-compatible") {
            error("Unsupported llm.provider='$provider' (POC supports only 'openai-compatible').")
        }

        val endpoint = (llm["endpoint"] as? String)?.trim()
            ?: error("Invalid YAML: llm.endpoint is required")

        val model = (llm["model"] as? String)?.trim()
            ?: error("Invalid YAML: llm.model is required")

        val apiKeyDirect = (llm["apiKey"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val apiKeyEnv = (llm["apiKeyEnv"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val apiKey = when {
            apiKeyDirect != null -> apiKeyDirect
            apiKeyEnv != null -> System.getenv(apiKeyEnv)?.takeIf { it.isNotBlank() }
                ?: error("Env var '$apiKeyEnv' is not set (needed for llm.apiKeyEnv).")
            else -> error("Invalid YAML: one of llm.apiKey or llm.apiKeyEnv is required")
        }

        val timeoutSeconds = when (val v = llm["timeoutSeconds"]) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            null -> 60L
            else -> error("Invalid YAML: llm.timeoutSeconds must be a number or string number")
        }

        return LlmSettings(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
//            timeoutSeconds = timeoutSeconds
        )
    }

    private fun findConfigFile(basePath: String): File? {
        for (name in configNames) {
            val f = File(basePath, name)
            if (f.exists() && f.isFile) return f
        }
        return null
    }
}