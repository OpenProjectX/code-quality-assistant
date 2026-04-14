package org.openprojectx.ai.plugin


import com.intellij.openapi.project.Project
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.core.GenerationPromptTemplate
import org.openprojectx.ai.plugin.llm.LlmAuthConfig
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.openprojectx.ai.plugin.llm.TemplateRequestConfig
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

object LlmSettingsLoader {

    private val configNames = listOf(".ai-test.yml", ".ai-test.yaml")
    private const val GLOBAL_JUNIT_PROFILE = "global/junit"
    private const val GLOBAL_KARATE_PROFILE = "global/karate"
    private const val GLOBAL_DIFF_REVIEW_PROFILE = "global/get diff review"

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

    fun loadSettingsModel(project: Project): AiTestSettingsModel {
        val root = readRootMap(project)
        val llm = root["llm"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val template = llm["template"] as? Map<*, *>
        val auth = llm["auth"] as? Map<*, *>
        val login = auth?.get("login") as? Map<*, *>
        val generation = root["generation"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val prompts = root["prompts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val promptGeneration = prompts["generation"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val defaults = generation["defaults"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val common = generation["common"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val frameworks = generation["frameworks"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val restAssured = frameworks[Framework.REST_ASSURED.id] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val karate = frameworks[Framework.KARATE.id] as? Map<*, *> ?: emptyMap<Any?, Any?>()

        return AiTestSettingsModel(
            llmProvider = llm.string("provider").ifBlank { "openai-compatible" },
            llmModel = llm.string("model"),
            llmEndpoint = llm.string("endpoint"),
            llmTimeoutSeconds = llm.string("timeoutSeconds").ifBlank { "60" },
            llmApiKey = llm.string("apiKey"),
            llmApiKeyEnv = llm.string("apiKeyEnv"),
            llmTemplateEnabled = template != null,
            llmTemplateMethod = template.string("method").ifBlank { "POST" },
            llmTemplateUrl = template.string("url"),
            llmTemplateHeaders = headersToText(template?.get("headers") as? Map<*, *>),
            llmTemplateBody = template.string("body"),
            llmTemplateResponsePath = template.string("responsePath"),
            loginEnabled = login != null,
            loginMethod = login.string("method").ifBlank { "POST" },
            loginUrl = login.string("url"),
            loginHeaders = headersToText(login?.get("headers") as? Map<*, *>),
            loginBody = login.string("body"),
            loginResponsePath = login.string("responsePath"),
            defaultFramework = Framework.fromIdOrNull(defaults.string("framework")) ?: AiTestDefaults.DEFAULT_FRAMEWORK,
            defaultClassName = defaults.string("className").ifBlank { AiTestDefaults.DEFAULT_CLASS_NAME },
            defaultBaseUrl = defaults.string("baseUrl"),
            defaultNotes = defaults.string("notes"),
            commonLocation = common.string("location"),
            restAssuredLocation = restAssured.string("location"),
            restAssuredPackageName = restAssured.string("packageName"),
            karateLocation = karate.string("location"),
            generationPromptWrapper = promptGeneration.string("wrapper").ifBlank { AiPromptDefaults.GENERATION_WRAPPER },
            generationPromptRestAssured = promptGeneration.string("restAssuredRules").ifBlank { AiPromptDefaults.GENERATION_REST_ASSURED },
            generationPromptKarate = promptGeneration.string("karateRules").ifBlank { AiPromptDefaults.GENERATION_KARATE },
            commitPrompt = prompts.string("commitMessage").ifBlank { AiPromptDefaults.COMMIT_MESSAGE },
            pullRequestPrompt = prompts.string("pullRequest").ifBlank { AiPromptDefaults.PULL_REQUEST },
            branchDiffPrompt = prompts.string("branchDiffSummary").ifBlank { AiPromptDefaults.BRANCH_DIFF_SUMMARY },
            generationPromptProfileDefault = prompts.map("generationProfiles").string("selected").ifBlank { PromptProfileSet.DEFAULT_NAME },
            generationPromptProfilesYaml = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "test",
                    parsePromptProfileItems(
                        prompts.map("generationProfiles").map("items"),
                        AiPromptDefaults.GENERATION_WRAPPER
                    )
                )
            ),
            commitPromptProfileDefault = GLOBAL_DIFF_REVIEW_PROFILE,
            commitPromptProfilesYaml = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "commit",
                    parsePromptProfileItems(
                    prompts.map("commitMessageProfiles").map("items"),
                    AiPromptDefaults.COMMIT_MESSAGE
                    )
                )
            ),
            branchDiffPromptProfileDefault = GLOBAL_DIFF_REVIEW_PROFILE,
            branchDiffPromptProfilesYaml = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "branchDiff",
                    parsePromptProfileItems(
                    prompts.map("branchDiffSummaryProfiles").map("items"),
                    AiPromptDefaults.BRANCH_DIFF_SUMMARY
                    )
                )
            )
        )
    }

    fun saveSettingsModel(project: Project, model: AiTestSettingsModel) {
        val root = readRootMap(project).toMutableLinkedMap()
        root["llm"] = buildLlmMap(root["llm"] as? Map<*, *>, model)
        root["generation"] = buildGenerationMap(root["generation"] as? Map<*, *>, model)
        root["prompts"] = buildPromptsMap(project, root["prompts"] as? Map<*, *>, model)
        writeRootMap(project, root)
    }

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
        val prompts = parsePromptOverrides(project, root)

        return AiTestConfig(
            llm = llm,
            generation = generation,
            prompts = prompts
        )
    }

    private fun parsePromptOverrides(project: Project, root: Map<*, *>): PromptOverrides {
        val prompts = root["prompts"] as? Map<*, *> ?: return PromptOverrides()
        val generation = prompts["generation"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

        return PromptOverrides(
            generation = GenerationPromptTemplate(
                wrapper = generation.string("wrapper").ifBlank { AiPromptDefaults.GENERATION_WRAPPER },
                restAssuredRules = generation.string("restAssuredRules").ifBlank { AiPromptDefaults.GENERATION_REST_ASSURED },
                karateRules = generation.string("karateRules").ifBlank { AiPromptDefaults.GENERATION_KARATE }
            ),
            commitMessage = prompts.string("commitMessage").ifBlank { AiPromptDefaults.COMMIT_MESSAGE },
            pullRequest = prompts.string("pullRequest").ifBlank { AiPromptDefaults.PULL_REQUEST },
            branchDiffSummary = prompts.string("branchDiffSummary").ifBlank { AiPromptDefaults.BRANCH_DIFF_SUMMARY },
            profiles = PromptProfiles(
                generation = parsePromptProfileSet(
                    profileMap = prompts.map("generationProfiles"),
                    defaultTemplate = generation.string("wrapper").ifBlank { AiPromptDefaults.GENERATION_WRAPPER },
                    globalCategory = "test",
                    project = project
                ),
                commitMessage = parsePromptProfileSet(
                    profileMap = prompts.map("commitMessageProfiles"),
                    defaultTemplate = prompts.string("commitMessage").ifBlank { AiPromptDefaults.COMMIT_MESSAGE },
                    globalCategory = "commit",
                    project = project
                ),
                branchDiffSummary = parsePromptProfileSet(
                    profileMap = prompts.map("branchDiffSummaryProfiles"),
                    defaultTemplate = prompts.string("branchDiffSummary").ifBlank { AiPromptDefaults.BRANCH_DIFF_SUMMARY },
                    globalCategory = "branchDiff",
                    project = project
                )
            )
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

    private fun buildLlmMap(existing: Map<*, *>?, model: AiTestSettingsModel): MutableMap<String, Any> {
        val llm = existing.toMutableLinkedMap()
        putIfNotBlank(llm, "provider", model.llmProvider)
        putIfNotBlank(llm, "model", model.llmModel)
        putIfNotBlank(llm, "endpoint", model.llmEndpoint)
        putIfNotBlank(llm, "timeoutSeconds", model.llmTimeoutSeconds)
        putIfNotBlank(llm, "apiKey", model.llmApiKey)
        putIfNotBlank(llm, "apiKeyEnv", model.llmApiKeyEnv)

        if (model.llmTemplateEnabled) {
            llm["template"] = buildTemplateMap(
                method = model.llmTemplateMethod,
                url = model.llmTemplateUrl,
                headersText = model.llmTemplateHeaders,
                body = model.llmTemplateBody,
                responsePath = model.llmTemplateResponsePath
            )
        } else {
            llm.remove("template")
        }

        if (model.loginEnabled) {
            llm["auth"] = linkedMapOf(
                "login" to buildTemplateMap(
                    method = model.loginMethod,
                    url = model.loginUrl,
                    headersText = model.loginHeaders,
                    body = model.loginBody,
                    responsePath = model.loginResponsePath
                )
            )
        } else {
            llm.remove("auth")
        }

        return llm
    }

    private fun buildGenerationMap(existing: Map<*, *>?, model: AiTestSettingsModel): MutableMap<String, Any> {
        val generation = existing.toMutableLinkedMap()

        generation["defaults"] = linkedMapOf<String, Any>(
            "framework" to model.defaultFramework.id,
            "className" to model.defaultClassName,
            "baseUrl" to model.defaultBaseUrl,
            "notes" to model.defaultNotes
        )

        generation["common"] = linkedMapOf<String, Any>(
            "location" to model.commonLocation
        )

        generation["frameworks"] = linkedMapOf<String, Any>(
            Framework.REST_ASSURED.id to linkedMapOf<String, Any>(
                "location" to model.restAssuredLocation,
                "packageName" to model.restAssuredPackageName
            ),
            Framework.KARATE.id to linkedMapOf<String, Any>(
                "location" to model.karateLocation
            )
        )

        return generation
    }

    private fun buildPromptsMap(project: Project, existing: Map<*, *>?, model: AiTestSettingsModel): MutableMap<String, Any> {
        val prompts = existing.toMutableLinkedMap()
        prompts["generation"] = linkedMapOf<String, Any>(
            "wrapper" to model.generationPromptWrapper,
            "restAssuredRules" to model.generationPromptRestAssured,
            "karateRules" to model.generationPromptKarate
        )
        prompts["commitMessage"] = model.commitPrompt
        prompts["pullRequest"] = model.pullRequestPrompt
        prompts["branchDiffSummary"] = model.branchDiffPrompt
        prompts["generationProfiles"] = buildPromptProfileMap(
            selected = model.generationPromptProfileDefault,
            yamlText = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "test",
                    parsePromptProfileItems(
                        Yaml().load<Any?>(model.generationPromptProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>(),
                        model.generationPromptWrapper
                    )
                )
            ),
            defaultTemplate = model.generationPromptWrapper
        )
        prompts["commitMessageProfiles"] = buildPromptProfileMap(
            selected = GLOBAL_DIFF_REVIEW_PROFILE,
            yamlText = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "commit",
                    parsePromptProfileItems(
                        Yaml().load<Any?>(model.commitPromptProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>(),
                        model.commitPrompt
                    )
                )
            ),
            defaultTemplate = model.commitPrompt
        )
        prompts["branchDiffSummaryProfiles"] = buildPromptProfileMap(
            selected = GLOBAL_DIFF_REVIEW_PROFILE,
            yamlText = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "branchDiff",
                    parsePromptProfileItems(
                        Yaml().load<Any?>(model.branchDiffPromptProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>(),
                        model.branchDiffPrompt
                    )
                )
            ),
            defaultTemplate = model.branchDiffPrompt
        )
        return prompts
    }

    private fun applyGlobalProfiles(project: Project, category: String, items: Map<String, String>): Map<String, String> {
        val globalPrompts = loadGlobalPrompts(project)[category].orEmpty()
        if (globalPrompts.isEmpty()) return items
        val normalized = linkedMapOf<String, String>()
        globalPrompts.forEach { (name, content) ->
            normalized[name] = content
        }
        items.forEach { (name, value) ->
            if (!globalPrompts.containsKey(name)) {
                normalized[name] = value
            }
        }
        return normalized
    }

    private fun loadGlobalPrompts(project: Project): Map<String, Map<String, String>> {
        val basePath = project.basePath ?: return emptyMap()
        val junit = readPromptFile("$basePath/prompts/generate-junit-prompt.md")
        val karate = readPromptFile("$basePath/prompts/generate-karate-prompt.md")
        val diffReview = readPromptFile("$basePath/prompts/git-diff-review-prompt.md")
            ?: readPromptFile("$basePath/prompts/diff-review.md")

        return mapOf(
            "test" to linkedMapOf<String, String>().apply {
                junit?.let { put(GLOBAL_JUNIT_PROFILE, it) }
                karate?.let { put(GLOBAL_KARATE_PROFILE, it) }
            },
            "commit" to linkedMapOf<String, String>().apply {
                diffReview?.let { put(GLOBAL_DIFF_REVIEW_PROFILE, it) }
            },
            "branchDiff" to linkedMapOf<String, String>().apply {
                diffReview?.let { put(GLOBAL_DIFF_REVIEW_PROFILE, it) }
            }
        )
    }

    private fun readPromptFile(path: String): String? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        return file.readText(Charsets.UTF_8).trim().ifBlank { null }
    }

    private fun parsePromptProfileSet(
        profileMap: Map<*, *>,
        defaultTemplate: String,
        globalCategory: String,
        project: Project
    ): PromptProfileSet {
        val items = applyGlobalProfiles(
            project,
            globalCategory,
            parsePromptProfileItems(profileMap.map("items"), defaultTemplate)
        )
        val selected = profileMap.string("selected").ifBlank { PromptProfileSet.DEFAULT_NAME }
        return PromptProfileSet(selected = selected, items = items)
    }

    private fun parsePromptProfileItems(map: Map<*, *>, defaultTemplate: String): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        map.forEach { (key, value) ->
            val name = key?.toString()?.trim().orEmpty()
            val template = value?.toString().orEmpty()
            if (name.isNotEmpty() && template.isNotBlank()) {
                parsed[name] = template
            }
        }
        if (parsed.isEmpty()) {
            parsed[PromptProfileSet.DEFAULT_NAME] = defaultTemplate
        } else if (!parsed.containsKey(PromptProfileSet.DEFAULT_NAME)) {
            parsed[PromptProfileSet.DEFAULT_NAME] = defaultTemplate
        }
        return parsed
    }

    private fun buildPromptProfileMap(selected: String, yamlText: String, defaultTemplate: String): Map<String, Any> {
        val parsedYaml = Yaml().load<Any?>(yamlText) as? Map<*, *>
        val items = parsePromptProfileItems(parsedYaml ?: emptyMap<Any?, Any?>(), defaultTemplate)
        return linkedMapOf(
            "selected" to selected.ifBlank { PromptProfileSet.DEFAULT_NAME },
            "items" to items
        )
    }

    private fun dumpPromptProfilesYaml(items: Map<String, String>): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        return Yaml(options).dump(items).trimEnd()
    }

    private fun buildTemplateMap(
        method: String,
        url: String,
        headersText: String,
        body: String,
        responsePath: String
    ): Map<String, Any> = linkedMapOf<String, Any>().apply {
        put("method", method.ifBlank { "POST" })
        put("url", url)
        val headers = headersFromText(headersText)
        if (headers.isNotEmpty()) {
            put("headers", headers)
        }
        put("body", body)
        put("responsePath", responsePath)
    }

    private fun readRootMap(project: Project): Map<String, Any?> {
        val text = readConfigText(project)
        val root = Yaml().load<Any?>(text) as? Map<*, *>
        return root.toMutableLinkedMap()
    }

    private fun writeRootMap(project: Project, root: Map<String, Any>) {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        val yaml = Yaml(options)
        writeConfigText(project, yaml.dump(root))
    }

    private fun headersToText(headers: Map<*, *>?): String {
        if (headers.isNullOrEmpty()) return ""
        return headers.entries.joinToString("\n") { (key, value) -> "${key.toString()}: ${value?.toString().orEmpty()}" }
    }

    private fun headersFromText(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val idx = line.indexOf(':')
                if (idx >= 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotEmpty()) {
                        result[key] = value
                    }
                }
            }
        return result
    }

    private fun Map<*, *>?.string(key: String): String =
        this?.get(key)?.toString()?.trim().orEmpty()

    private fun Map<*, *>?.map(key: String): Map<*, *> =
        this?.get(key) as? Map<*, *> ?: emptyMap<Any?, Any?>()

    private fun Map<*, *>?.toMutableLinkedMap(): MutableMap<String, Any> {
        val result = linkedMapOf<String, Any>()
        this?.forEach { (key, value) ->
            val keyText = key?.toString() ?: return@forEach
            when (value) {
                is Map<*, *> -> result[keyText] = value.toMutableLinkedMap()
                is List<*> -> result[keyText] = value.toMutableList()
                null -> {}
                else -> result[keyText] = value
            }
        }
        return result
    }

    private fun putIfNotBlank(target: MutableMap<String, Any>, key: String, value: String) {
        if (value.isBlank()) {
            target.remove(key)
        } else {
            target[key] = value
        }
    }
}
