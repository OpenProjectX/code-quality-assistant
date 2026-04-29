package org.openprojectx.ai.plugin


import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.core.GenerationPromptTemplate
import org.openprojectx.ai.plugin.pr.GitRemoteParser
import org.openprojectx.ai.plugin.llm.LlmAuthConfig
import org.openprojectx.ai.plugin.llm.LlmSettings
import org.openprojectx.ai.plugin.llm.TemplateRequestConfig
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

object LlmSettingsLoader {

    private val configNames = listOf(".ai-test.yml", ".ai-test.yaml")
    private val userHome: String = System.getProperty("user.home")
    private val appHomeDirName = ".codeimprover"
    private const val GLOBAL_JUNIT_PROFILE = "[ADA] junit"
    private const val GLOBAL_KARATE_PROFILE = "[ADA] karate"
    private const val GLOBAL_DIFF_REVIEW_PROFILE = "[ADA] get diff review"
    private val json by lazy { Json { ignoreUnknownKeys = true } }

    private data class BitbucketPromptRepoConfig(
        val enabled: Boolean,
        val repoUrl: String,
        val branch: String,
        val token: String,
        val username: String,
        val password: String
    )

    private data class GlobalPromptMeta(
        val category: String,
        val name: String,
        val updatedAt: Instant,
        val template: String,
        val sourcePriority: Int
    )

    data class PromptUpdateStatus(
        val configured: Boolean,
        val remoteCount: Int,
        val cachedCount: Int,
        val hasUpdates: Boolean,
        val message: String
    )

    fun load(project: Project): LlmSettings = loadConfig(project).llm

    fun readConfigText(project: Project): String {
        val configFile = findOrCreateConfigFile()
        return configFile.readText(Charsets.UTF_8)
    }

    fun writeConfigText(project: Project, text: String) {
        val configFile = findOrCreateConfigFile()
        configFile.writeText(text, Charsets.UTF_8)
    }

    fun configFilePath(project: Project): String =
        findOrCreateConfigFile().absolutePath

    fun importConfigFromRepo(project: Project): String {
        val root = readRootMap(project)
        val prompts = root["prompts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val remoteRepo = parseBitbucketPromptRepoConfig(prompts.map("remoteRepo"))
        if (remoteRepo.repoUrl.isBlank()) {
            error("Cannot import repo config: prompts.remoteRepo.url is not configured.")
        }
        val repo = GitRemoteParser.parse(remoteRepo.repoUrl)
        val configPath = ".ai-test.yaml"
        val sourceText = runCatching {
            loadBitbucketPromptRaw(
                project = project,
                host = repo.host,
                projectKey = repo.projectKey,
                repoSlug = repo.repoSlug,
                path = configPath,
                branch = remoteRepo.branch,
                config = remoteRepo
            )
        }.getOrElse {
            error("Cannot find repo config in Bitbucket. Expected file: $configPath")
        }
        val target = findOrCreateConfigFile()
        target.writeText(sourceText, Charsets.UTF_8)
        return "${remoteRepo.repoUrl}@$configPath"
    }

    fun checkBitbucketPromptUpdates(project: Project): PromptUpdateStatus {
        val model = loadSettingsModel(project)
        if (model.bitbucketPromptRepoUrl.isBlank()) {
            return PromptUpdateStatus(
                configured = false,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = "Bitbucket prompt repo URL is not configured."
            )
        }

        return runCatching {
            val remoteConfig = BitbucketPromptRepoConfig(
                enabled = model.bitbucketPromptRepoEnabled,
                repoUrl = model.bitbucketPromptRepoUrl,
                branch = model.bitbucketPromptRepoBranch,
                token = model.bitbucketPromptRepoToken,
                username = model.bitbucketPromptRepoUsername,
                password = model.bitbucketPromptRepoPassword
            )
            val remoteGlobalKeys = loadGlobalPrompts(project, remoteConfig)
                .values
                .flatMap { it.keys }
                .filter { it.startsWith("global/") }
                .toSet()
            val cachedGlobalKeys = readCachedGlobalPromptKeys(project)
            val hasUpdates = remoteGlobalKeys != cachedGlobalKeys
            val addedPromptKeys = (remoteGlobalKeys - cachedGlobalKeys).sorted()
            if (addedPromptKeys.isNotEmpty()) {
                RuntimeLogStore.append("INFO | Bitbucket Prompt Repo | Added prompts: ${addedPromptKeys.joinToString()}")
            }
            PromptUpdateStatus(
                configured = true,
                remoteCount = remoteGlobalKeys.size,
                cachedCount = cachedGlobalKeys.size,
                hasUpdates = hasUpdates,
                message = if (hasUpdates) {
                    if (addedPromptKeys.isNotEmpty()) {
                        "New prompt updates are available. Added: ${addedPromptKeys.joinToString()}"
                    } else {
                        "New prompt updates are available."
                    }
                } else {
                    "Prompt cache is up to date."
                }
            )
        }.getOrElse { ex ->
            PromptUpdateStatus(
                configured = true,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = ex.message ?: ex.toString()
            )
        }
    }

    fun pullBitbucketPromptUpdates(project: Project): PromptUpdateStatus {
        val latest = loadSettingsModel(project)
        saveSettingsModel(project, latest)
        return checkBitbucketPromptUpdates(project)
    }

    fun loadSettingsModel(project: Project): AiTestSettingsModel {
        val root = readRootMap(project)
        val llm = root["llm"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val template = llm["template"] as? Map<*, *>
        val auth = llm["auth"] as? Map<*, *>
        val login = auth?.get("login") as? Map<*, *>
        val http = llm["http"] as? Map<*, *>
        val ui = root["ui"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val prompts = root["prompts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val remoteRepo = prompts.map("remoteRepo")
        val promptGeneration = prompts["generation"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

        return AiTestSettingsModel(
            llmProvider = llm.string("provider").ifBlank { "openai-compatible" },
            llmModel = llm.string("model"),
            llmEndpoint = llm.string("endpoint"),
            llmTimeoutSeconds = llm.string("timeoutSeconds").ifBlank { "60" },
            llmApiKey = llm.string("apiKey"),
            llmApiKeyEnv = llm.string("apiKeyEnv"),
            httpDisableTlsVerification = http?.get("disableTlsVerification") as? Boolean ?: false,
            showLogTab = ui["showLogTab"] as? Boolean ?: false,
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
                    parseBitbucketPromptRepoConfig(remoteRepo),
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
                    parseBitbucketPromptRepoConfig(remoteRepo),
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
                    parseBitbucketPromptRepoConfig(remoteRepo),
                    parsePromptProfileItems(
                    prompts.map("branchDiffSummaryProfiles").map("items"),
                    AiPromptDefaults.BRANCH_DIFF_SUMMARY
                    )
                )
            ),
            codeGeneratePromptProfileDefault = prompts.map("codeGenerateProfiles").string("selected").ifBlank { PromptProfileSet.DEFAULT_NAME },
            codeGeneratePromptProfilesYaml = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "codeGenerate",
                    parseBitbucketPromptRepoConfig(remoteRepo),
                    parsePromptProfileItems(
                        prompts.map("codeGenerateProfiles").map("items"),
                        AiPromptDefaults.CODE_GENERATE
                    )
                )
            ),
            codeReviewPromptProfileDefault = prompts.map("codeReviewProfiles").string("selected").ifBlank { PromptProfileSet.DEFAULT_NAME },
            codeReviewPromptProfilesYaml = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "codeReview",
                    parseBitbucketPromptRepoConfig(remoteRepo),
                    parsePromptProfileItems(
                        prompts.map("codeReviewProfiles").map("items"),
                        AiPromptDefaults.CODE_REVIEW
                    )
                )
            ),
            bitbucketPromptRepoEnabled = remoteRepo["enabled"] as? Boolean ?: false,
            bitbucketPromptRepoUrl = remoteRepo.string("url"),
            bitbucketPromptRepoBranch = remoteRepo.string("branch").ifBlank { "main" },
            bitbucketPromptRepoToken = remoteRepo.string("token"),
            bitbucketPromptRepoUsername = remoteRepo.string("username"),
            bitbucketPromptRepoPassword = remoteRepo.string("password")
        )
    }

    fun saveSettingsModel(project: Project, model: AiTestSettingsModel) {
        val root = readRootMap(project).toMutableLinkedMap()
        root["llm"] = buildLlmMap(root["llm"] as? Map<*, *>, model)
        root["ui"] = buildUiMap(root["ui"] as? Map<*, *>, model)
        root.remove("generation")
        root["prompts"] = buildPromptsMap(project, root["prompts"] as? Map<*, *>, model)
        writeRootMap(project, root)
    }

    fun loadConfig(project: Project): AiTestConfig {
        val configFile = findConfigFile()
            ?: error("AI TestGen config not found. Expected one of: ${configNames.joinToString()} under ${configHomeDir().absolutePath}.")

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
        val remoteRepo = parseBitbucketPromptRepoConfig(prompts.map("remoteRepo"))
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
                    project = project,
                    remoteRepoConfig = remoteRepo
                ),
                commitMessage = parsePromptProfileSet(
                    profileMap = prompts.map("commitMessageProfiles"),
                    defaultTemplate = prompts.string("commitMessage").ifBlank { AiPromptDefaults.COMMIT_MESSAGE },
                    globalCategory = "commit",
                    project = project,
                    remoteRepoConfig = remoteRepo
                ),
                branchDiffSummary = parsePromptProfileSet(
                    profileMap = prompts.map("branchDiffSummaryProfiles"),
                    defaultTemplate = prompts.string("branchDiffSummary").ifBlank { AiPromptDefaults.BRANCH_DIFF_SUMMARY },
                    globalCategory = "branchDiff",
                    project = project,
                    remoteRepoConfig = remoteRepo
                ),
                codeGenerate = parsePromptProfileSet(
                    profileMap = prompts.map("codeGenerateProfiles"),
                    defaultTemplate = AiPromptDefaults.CODE_GENERATE,
                    globalCategory = "codeGenerate",
                    project = project,
                    remoteRepoConfig = remoteRepo
                ),
                codeReview = parsePromptProfileSet(
                    profileMap = prompts.map("codeReviewProfiles"),
                    defaultTemplate = AiPromptDefaults.CODE_REVIEW,
                    globalCategory = "codeReview",
                    project = project,
                    remoteRepoConfig = remoteRepo
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
//                ?: error("Env var '$apiKeyEnv' is not set (needed for llm.apiKeyEnv).")
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

        val httpMap = llm["http"] as? Map<*, *>
        val disableTlsVerification = httpMap?.get("disableTlsVerification") as? Boolean ?: false

        return LlmSettings(
            provider = provider,
            model = model,
            timeoutSeconds = timeoutSeconds,
            apiKey = apiKey,
            endpoint = endpoint,
            template = template,
            auth = auth,
            httpDisableTlsVerification = disableTlsVerification
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

    private fun parseGenerationConfig(@Suppress("UNUSED_PARAMETER") root: Map<*, *>): GenerationConfig {
        return GenerationConfig()
    }

    private fun findConfigFile(): File? {
        val appHome = configHomeDir()
        for (name in configNames) {
            val f = File(appHome, name)
            if (f.exists() && f.isFile) return f
        }
        for (name in configNames) {
            val f = File(userHome, name)
            if (f.exists() && f.isFile) return f
        }
        return null
    }

    private fun findOrCreateConfigFile(): File {
        val existing = findConfigFile()
        val targetDir = configHomeDir()
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        if (existing != null) {
            val target = File(targetDir, ".ai-test.yaml")
            if (existing.absolutePath != target.absolutePath) {
                if (!target.exists()) {
                    target.writeText(existing.readText(Charsets.UTF_8), Charsets.UTF_8)
                }
                return target
            }
            return existing
        }

        val file = File(targetDir, ".ai-test.yaml")
        if (!file.exists()) {
            file.writeText(defaultConfigTemplate(), Charsets.UTF_8)
        }
        return file
    }

    private fun configHomeDir(): File = File(userHome, appHomeDirName)

    private fun defaultConfigTemplate(): String = """
        llm:
          provider: openai-compatible
          endpoint: https://api.openai.com/v1/chat/completions
          model: gpt-4.1
          apiKeyEnv: OPENAI_API_KEY

    """.trimIndent() + "\n"

    private fun buildLlmMap(existing: Map<*, *>?, model: AiTestSettingsModel): MutableMap<String, Any> {
        val llm = existing.toMutableLinkedMap()
        putIfNotBlank(llm, "provider", model.llmProvider)
        putIfNotBlank(llm, "model", model.llmModel)
        putIfNotBlank(llm, "endpoint", model.llmEndpoint)
        putIfNotBlank(llm, "timeoutSeconds", model.llmTimeoutSeconds)
        putIfNotBlank(llm, "apiKey", model.llmApiKey)
        putIfNotBlank(llm, "apiKeyEnv", model.llmApiKeyEnv)

        if (model.httpDisableTlsVerification) {
            llm["http"] = linkedMapOf("disableTlsVerification" to true)
        } else {
            llm.remove("http")
        }

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

    private fun buildPromptsMap(project: Project, existing: Map<*, *>?, model: AiTestSettingsModel): MutableMap<String, Any> {
        val prompts = existing.toMutableLinkedMap()
        val remoteRepoConfig = BitbucketPromptRepoConfig(
            enabled = model.bitbucketPromptRepoEnabled,
            repoUrl = model.bitbucketPromptRepoUrl,
            branch = model.bitbucketPromptRepoBranch,
            token = model.bitbucketPromptRepoToken,
            username = model.bitbucketPromptRepoUsername,
            password = model.bitbucketPromptRepoPassword
        )
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
                    remoteRepoConfig,
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
                    remoteRepoConfig,
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
                    remoteRepoConfig,
                    parsePromptProfileItems(
                        Yaml().load<Any?>(model.branchDiffPromptProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>(),
                        model.branchDiffPrompt
                    )
                )
            ),
            defaultTemplate = model.branchDiffPrompt
        )
        prompts["codeGenerateProfiles"] = buildPromptProfileMap(
            selected = model.codeGeneratePromptProfileDefault,
            yamlText = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "codeGenerate",
                    remoteRepoConfig,
                    parsePromptProfileItems(
                        Yaml().load<Any?>(model.codeGeneratePromptProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>(),
                        AiPromptDefaults.CODE_GENERATE
                    )
                )
            ),
            defaultTemplate = AiPromptDefaults.CODE_GENERATE
        )
        prompts["codeReviewProfiles"] = buildPromptProfileMap(
            selected = model.codeReviewPromptProfileDefault,
            yamlText = dumpPromptProfilesYaml(
                applyGlobalProfiles(
                    project,
                    "codeReview",
                    remoteRepoConfig,
                    parsePromptProfileItems(
                        Yaml().load<Any?>(model.codeReviewPromptProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>(),
                        AiPromptDefaults.CODE_REVIEW
                    )
                )
            ),
            defaultTemplate = AiPromptDefaults.CODE_REVIEW
        )
        prompts["remoteRepo"] = linkedMapOf<String, Any>(
            "enabled" to model.bitbucketPromptRepoEnabled,
            "url" to model.bitbucketPromptRepoUrl,
            "branch" to model.bitbucketPromptRepoBranch,
            "token" to model.bitbucketPromptRepoToken,
            "username" to model.bitbucketPromptRepoUsername,
            "password" to model.bitbucketPromptRepoPassword
        )
        return prompts
    }

    private fun buildUiMap(existing: Map<*, *>?, model: AiTestSettingsModel): MutableMap<String, Any> {
        val ui = existing.toMutableLinkedMap()
        ui["showLogTab"] = model.showLogTab
        return ui
    }

    private fun applyGlobalProfiles(
        project: Project,
        category: String,
        remoteRepoConfig: BitbucketPromptRepoConfig,
        items: Map<String, String>
    ): Map<String, String> {
        val globalPrompts = loadGlobalPrompts(project, remoteRepoConfig)[category].orEmpty()
        if (globalPrompts.isEmpty()) return items
        val normalized = linkedMapOf<String, String>()
        globalPrompts.forEach { (name, content) ->
            normalized[name] = content
        }
        items.forEach { (name, value) ->
            if (!globalPrompts.containsKey(name) && !name.startsWith("global/")) {
                normalized[name] = value
            }
        }
        return normalized
    }

    private fun loadGlobalPrompts(project: Project, remoteRepoConfig: BitbucketPromptRepoConfig): Map<String, Map<String, String>> {
        val basePath = project.basePath ?: return emptyMap()
        val junit = readPromptFile("$basePath/prompts/generate-junit-prompt.md")
        val karate = readPromptFile("$basePath/prompts/generate-karate-prompt.md")
        val diffReview = readPromptFile("$basePath/prompts/git-diff-review-prompt.md")
            ?: readPromptFile("$basePath/prompts/diff-review.md")
        val entries = mutableListOf<GlobalPromptMeta>()
        junit?.let {
            entries += GlobalPromptMeta("test", GLOBAL_JUNIT_PROFILE, Instant.EPOCH, it, sourcePriority = 1)
        }
        karate?.let {
            entries += GlobalPromptMeta("test", GLOBAL_KARATE_PROFILE, Instant.EPOCH, it, sourcePriority = 1)
        }
        diffReview?.let {
            entries += GlobalPromptMeta("commit", GLOBAL_DIFF_REVIEW_PROFILE, Instant.EPOCH, it, sourcePriority = 1)
            entries += GlobalPromptMeta("branchDiff", GLOBAL_DIFF_REVIEW_PROFILE, Instant.EPOCH, it, sourcePriority = 1)
        }
        entries += fetchBitbucketGlobalPromptEntries(project, remoteRepoConfig)

        return entries
            .groupBy { it.category }
            .mapValues { (_, categoryEntries) ->
                val newestByName = linkedMapOf<String, GlobalPromptMeta>()
                categoryEntries.forEach { entry ->
                    val existing = newestByName[entry.name]
                    if (existing == null ||
                        entry.updatedAt.isAfter(existing.updatedAt) ||
                        (entry.updatedAt == existing.updatedAt && entry.sourcePriority > existing.sourcePriority)
                    ) {
                        newestByName[entry.name] = entry
                    }
                }
                newestByName.values
                    .sortedWith(compareByDescending<GlobalPromptMeta> { it.updatedAt }.thenBy { it.name })
                    .associateTo(linkedMapOf()) { meta ->
                        val key = if (meta.sourcePriority > 1) {
                            "global/${meta.name} [${meta.updatedAt}]"
                        } else {
                            meta.name
                        }
                        key to meta.template
                    }
            }
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
        project: Project,
        remoteRepoConfig: BitbucketPromptRepoConfig
    ): PromptProfileSet {
        val items = applyGlobalProfiles(
            project,
            globalCategory,
            remoteRepoConfig,
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

    private fun parseBitbucketPromptRepoConfig(map: Map<*, *>): BitbucketPromptRepoConfig {
        return BitbucketPromptRepoConfig(
            enabled = map["enabled"] as? Boolean ?: false,
            repoUrl = map.string("url"),
            branch = map.string("branch").ifBlank { "main" },
            token = map.string("token"),
            username = map.string("username"),
            password = map.string("password")
        )
    }

    private fun fetchBitbucketGlobalPromptEntries(project: Project, config: BitbucketPromptRepoConfig): List<GlobalPromptMeta> {
        if (config.repoUrl.isBlank()) return emptyList()
        return runCatching {
            val repo = GitRemoteParser.parse(config.repoUrl)
            val filePaths = loadBitbucketPromptFilePaths(project, repo.host, repo.projectKey, repo.repoSlug, config.branch, config)
            filePaths.mapNotNull { path ->
                val content = loadBitbucketPromptRaw(project, repo.host, repo.projectKey, repo.repoSlug, path, config.branch, config)
                parseGlobalPromptMeta(path, content)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseGlobalPromptMeta(path: String, content: String): GlobalPromptMeta? {
        if (!isPromptMarkdownPath(path)) return null
        val text = content.trim()
        if (text.isBlank()) return null
        val nameMatch = Regex("(?im)^name\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val timeMatch = Regex("(?im)^time\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val typeMatch = Regex("(?im)^type\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()

        val category = resolveCategory(path, typeMatch) ?: "test"
        val name = nameMatch?.takeIf { it.isNotBlank() } ?: File(path).nameWithoutExtension
        val time = parseInstantOrEpoch(timeMatch)
        return GlobalPromptMeta(
            category = category,
            name = name,
            updatedAt = time,
            template = text,
            sourcePriority = 2
        )
    }

    private fun parseInstantOrEpoch(raw: String?): Instant {
        if (raw.isNullOrBlank()) return Instant.EPOCH
        return try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            Instant.EPOCH
        }
    }

    private fun resolveCategory(path: String, typeRaw: String?): String? {
        val type = typeRaw?.trim()?.lowercase()
        if (type == "test" || type == "commit" || type == "branchdiff" || type == "codegenerate" || type == "codereview") {
            return when (type) {
                "branchdiff" -> "branchDiff"
                "codegenerate" -> "codeGenerate"
                "codereview" -> "codeReview"
                else -> type
            }
        }

        val normalized = path.replace('\\', '/').lowercase()
        val rootPrefix = when {
            normalized.startsWith("prompts/") -> "prompts/"
            normalized.startsWith("prompt/") -> "prompt/"
            else -> ""
        }
        if (rootPrefix.isNotEmpty()) {
            val relative = normalized.removePrefix(rootPrefix)
            val firstSegment = relative.substringBefore('/')
            when (firstSegment) {
                "test", "tests" -> return "test"
                "commit", "commits" -> return "commit"
                "branchdiff", "branch-diff", "branch_diff" -> return "branchDiff"
                "codegenerate", "code-generate", "code_generate" -> return "codeGenerate"
                "codereview", "code-review", "code_review" -> return "codeReview"
            }
        }

        val lowerPath = path.lowercase()
        return when {
            lowerPath.contains("junit") || lowerPath.contains("karate") || lowerPath.contains("test") -> "test"
            lowerPath.contains("commit") -> "commit"
            lowerPath.contains("diff") || lowerPath.contains("branch") -> "branchDiff"
            lowerPath.contains("code-generate") || lowerPath.contains("code_generate") -> "codeGenerate"
            lowerPath.contains("code-review") || lowerPath.contains("code_review") -> "codeReview"
            else -> null
        }
    }

    private fun isPromptMarkdownPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return (normalized.startsWith("prompts/") || normalized.startsWith("prompt/")) && normalized.endsWith(".md")
    }

    private fun loadBitbucketPromptFilePaths(
        project: Project,
        host: String,
        projectKey: String,
        repoSlug: String,
        branch: String,
        config: BitbucketPromptRepoConfig
    ): List<String> {
        val encodedBranch = URLEncoder.encode("refs/heads/$branch", StandardCharsets.UTF_8)
        val url = "https://$host/rest/api/1.0/projects/$projectKey/repos/$repoSlug/files?at=$encodedBranch&limit=1000"
        val body = bitbucketGet(project, url, config)
        val values = json.parseToJsonElement(body).jsonObject["values"]?.jsonArray ?: return emptyList()
        return values.mapNotNull { it.jsonPrimitive.contentOrNull }
            .filter { isPromptMarkdownPath(it) }
    }

    private fun loadBitbucketPromptRaw(
        project: Project,
        host: String,
        projectKey: String,
        repoSlug: String,
        path: String,
        branch: String,
        config: BitbucketPromptRepoConfig
    ): String {
        val encodedPath = path.split("/").joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
        val encodedBranch = URLEncoder.encode("refs/heads/$branch", StandardCharsets.UTF_8)
        val url = "https://$host/rest/api/1.0/projects/$projectKey/repos/$repoSlug/raw/$encodedPath?at=$encodedBranch"
        return bitbucketGet(project, url, config)
    }

    private fun bitbucketGet(project: Project, url: String, config: BitbucketPromptRepoConfig): String {
        val llmAuthService = LlmAuthSessionService.getInstance(project)
        val savedLoginCredentials = llmAuthService.loadSavedLoginCredentialsForCurrentSettings()
        val credentialPairs = mutableListOf<Pair<String, String>>()
        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            credentialPairs += config.username to config.password
        }
        if (savedLoginCredentials != null) {
            credentialPairs += savedLoginCredentials.username to savedLoginCredentials.password
        }
        return runCatching {
            bitbucketGetWithCredentials(url, config.token, credentialPairs)
        }.getOrElse {
            val prompted = llmAuthService.promptLoginCredentialsForCurrentSettings()
            bitbucketGetWithCredentials(url, config.token, listOf(prompted.username to prompted.password))
        }
    }

    private fun bitbucketGetWithCredentials(url: String, token: String, credentialPairs: List<Pair<String, String>>): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 60_000
        conn.readTimeout = 60_000
        val normalized = token.trim()
        if (normalized.isNotBlank()) {
            if (normalized.contains(":")) {
                val basic = Base64.getEncoder().encodeToString(normalized.toByteArray(Charsets.UTF_8))
                conn.setRequestProperty("Authorization", "Basic $basic")
            } else {
                conn.setRequestProperty("Authorization", "Bearer $normalized")
            }
        } else if (credentialPairs.isNotEmpty()) {
            val (username, password) = credentialPairs.first()
            val basicRaw = "$username:$password"
            val basic = Base64.getEncoder().encodeToString(basicRaw.toByteArray(Charsets.UTF_8))
            conn.setRequestProperty("Authorization", "Basic $basic")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        if (code !in 200..299) {
            error("Bitbucket API request failed ($code) for $url. Response: ${body.ifBlank { "<empty>" }}")
        }
        return body
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

    private fun readCachedGlobalPromptKeys(project: Project): Set<String> {
        val root = readRootMap(project)
        val prompts = root["prompts"] as? Map<*, *> ?: return emptySet()
        val profileKeys = listOf(
            "generationProfiles",
            "commitMessageProfiles",
            "branchDiffSummaryProfiles",
            "codeGenerateProfiles",
            "codeReviewProfiles"
        )
        return profileKeys
            .flatMap { key ->
                val items = (prompts[key] as? Map<*, *>)?.map("items") ?: emptyMap<Any?, Any?>()
                items.keys.mapNotNull { it?.toString() }
            }
            .filter { it.startsWith("global/") }
            .toSet()
    }
}
