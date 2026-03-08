package org.openprojectx.ai.plugin


import com.intellij.openapi.project.Project
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.yaml.snakeyaml.Yaml
import java.io.File

object LlmSettingsLoader {

    private val configNames = listOf(".ai-test.yml", ".ai-test.yaml")

    fun load(project: Project): LlmSettings = loadConfig(project).llm

    fun loadConfig(project: Project): AiTestConfig {
        val basePath = project.basePath ?: error("Project basePath is null")
        val configFile = findConfigFile(basePath)
            ?: error("AI TestGen config not found. Expected one of: ${configNames.joinToString()} at project root.")

        val text = configFile.readText(Charsets.UTF_8)
        val yaml = Yaml()
        val root = yaml.load<Any?>(text) as? Map<*, *>
            ?: error("Invalid YAML: root is not a map")

        val llm = parseLlmSettings(root)
        val generation = parseGenerationConfig(root)

        return AiTestConfig(
            llm = llm,
            generation = generation
        )
    }

    private fun parseLlmSettings(root: Map<*, *>): LlmSettings {
        val llm = (root["llm"] as? Map<*, *>) ?: error("Invalid YAML: missing top-level 'llm' object")

        val provider = (llm["provider"] as? String)?.trim().orEmpty().ifEmpty { "openai-compatible" }
        if (provider != "openai-compatible" && provider != "aliyun") {
            error("Unsupported llm.provider='$provider'.")
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
            timeoutSeconds = timeoutSeconds!!
        )
    }

    private fun parseGenerationConfig(root: Map<*, *>): GenerationConfig {
        val generation = root["generation"] as? Map<*, *> ?: return GenerationConfig()

        val defaults = generation["defaults"] as? Map<*, *>
        val common = generation["common"] as? Map<*, *>
        val frameworks = generation["frameworks"] as? Map<*, *>

        val defaultFramework = ((defaults?.get("framework") as? String)?.trim())
            ?.let { Framework.fromIdOrNull(it) }
            ?: AiTestDefaults.DEFAULT_FRAMEWORK

        val defaultClassName = (defaults?.get("className") as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: AiTestDefaults.DEFAULT_CLASS_NAME

        val defaultBaseUrl = (defaults?.get("baseUrl") as? String)?.trim()
            ?: AiTestDefaults.DEFAULT_BASE_URL

        val defaultNotes = (defaults?.get("notes") as? String)?.trim()
            ?: AiTestDefaults.DEFAULT_NOTES

        val commonDefaults = CommonGenerationDefaults(
            location = (common?.get("location") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        )

        val frameworkDefaults = Framework.entries.associateWith { framework ->
            val raw = frameworks?.get(framework.id) as? Map<*, *>
            FrameworkGenerationDefaults(
                location = (raw?.get("location") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                packageName = (raw?.get("packageName") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            )
        }.filterValues { it.location != null || it.packageName != null }

        return GenerationConfig(
            defaultFramework = defaultFramework,
            defaultClassName = defaultClassName,
            defaultBaseUrl = defaultBaseUrl,
            defaultNotes = defaultNotes,
            common = commonDefaults,
            frameworks = frameworkDefaults
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