package org.openprojectx.ai.plugin


import com.intellij.openapi.project.Project
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.llm.LlmAuthConfig
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.openprojectx.ai.plugin.llm.TemplateRequestConfig
import org.yaml.snakeyaml.Yaml
import java.io.File

object LlmSettingsLoader {

    private val configNames = listOf(".ai-test.yml", ".ai-test.yaml")

    fun load(project: Project): LlmSettings = loadConfig(project).llm

    fun readConfigText(project: Project): String {
        val configFile = findOrCreateConfigFile(project.basePath ?: error("Project basePath is null"))
        return configFile.readText(Charsets.UTF_8)
    }

    fun writeConfigText(project: Project, text: String) {
        val configFile = findOrCreateConfigFile(project.basePath ?: error("Project basePath is null"))
        configFile.writeText(text, Charsets.UTF_8)
    }

    fun configFilePath(project: Project): String =
        findOrCreateConfigFile(project.basePath ?: error("Project basePath is null")).absolutePath

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

        val model = (llm["model"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("Invalid YAML: llm.model is required")

        val apiKeyDirect = (llm["apiKey"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val apiKeyEnv = (llm["apiKeyEnv"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val apiKey = when {
            apiKeyDirect != null -> apiKeyDirect
            apiKeyEnv != null -> System.getenv(apiKeyEnv)?.takeIf { it.isNotBlank() }
                ?: error("Env var '$apiKeyEnv' is not set (needed for llm.apiKeyEnv).")
            else -> null
        }

        val timeoutSeconds = when (val v = llm["timeoutSeconds"]) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            null -> 60L
            else -> error("Invalid YAML: llm.timeoutSeconds must be a number or string number")
        } ?: 60L

        val endpoint = (llm["endpoint"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val template = (llm["template"] as? Map<*, *>)?.let {
            parseTemplateRequestConfig(it, "llm.template")
        }

        val auth = (llm["auth"] as? Map<*, *>)?.let { authMap ->
            val login = (authMap["login"] as? Map<*, *>)
                ?: error("Invalid YAML: llm.auth.login is required")
            LlmAuthConfig(login = parseTemplateRequestConfig(login, "llm.auth.login"))
        }

        return LlmSettings(
            provider = provider,
            model = model,
            timeoutSeconds = timeoutSeconds,
            apiKey = apiKey,
            endpoint = endpoint,
            template = template,
            auth = auth
        )
    }

    private fun parseTemplateRequestConfig(templateMap: Map<*, *>, path: String): TemplateRequestConfig {
        val method = (templateMap["method"] as? String)?.trim().orEmpty().ifEmpty { "POST" }
        val url = (templateMap["url"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("Invalid YAML: $path.url is required")
        val body = templateMap["body"] as? String
            ?: error("Invalid YAML: $path.body is required")
        val responsePath = (templateMap["responsePath"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("Invalid YAML: $path.responsePath is required")

        val headers = ((templateMap["headers"] as? Map<*, *>) ?: emptyMap<Any?, Any?>())
            .mapKeys { (k, _) -> k?.toString() ?: error("Invalid YAML: $path.headers contains null key") }
            .mapValues { (_, v) -> v?.toString() ?: "" }

        return TemplateRequestConfig(
            method = method,
            url = url,
            headers = headers,
            body = body,
            responsePath = responsePath
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

    private fun findOrCreateConfigFile(basePath: String): File {
        val existing = findConfigFile(basePath)
        if (existing != null) return existing

        val file = File(basePath, ".ai-test.yaml")
        if (!file.exists()) {
            file.writeText(defaultConfigTemplate(), Charsets.UTF_8)
        }
        return file
    }

    private fun defaultConfigTemplate(): String = """
        llm:
          provider: openai-compatible
          endpoint: https://api.openai.com/v1/chat/completions
          model: gpt-4.1
          apiKeyEnv: OPENAI_API_KEY

        generation:
          defaults:
            framework: karate
            className: OpenApiGeneratedTests
            baseUrl: ""
            notes: ""
          common:
            location: src/test/resources/karate
          frameworks:
            restassured:
              location: src/test/java
              packageName: org.openprojectx.ai.plugin.generated
            karate:
              location: src/test/resources/karate
    """.trimIndent() + "\n"
}
