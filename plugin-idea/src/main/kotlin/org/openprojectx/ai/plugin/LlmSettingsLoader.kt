package org.openprojectx.ai.plugin


import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.core.GenerationPromptTemplate
import org.openprojectx.ai.plugin.pr.GitHostingProviderType
import org.openprojectx.ai.plugin.pr.GitRemoteParser
import org.openprojectx.ai.plugin.pr.RepositoryRef
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
        val password: String,
        val configImportPath: String = ""
    )

    private data class BitbucketCredential(
        val source: String,
        val username: String,
        val password: String
    )

    private data class GlobalPromptMeta(
        val category: String,
        val name: String,
        val updatedAt: Instant,
        val template: String,
        val sourcePriority: Int
    ) {
        val cacheKey: String
            get() = "global/$name [$updatedAt]"
    }

    private data class PromptProfileTarget(
        val category: String,
        val profileKey: String,
        val defaultTemplate: String,
        val selectedFallback: String
    )

    data class PromptUpdateStatus(
        val configured: Boolean,
        val remoteCount: Int,
        val cachedCount: Int,
        val hasUpdates: Boolean,
        val message: String,
        val error: Boolean = false
    )

    fun load(project: Project): LlmSettings = loadLlmSettings(project)

    fun loadLlmSettings(project: Project): LlmSettings {
        return parseLlmSettings(readRootMap(project))
    }

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
        return importConfigFromRepo(project, parseBitbucketPromptRepoConfig(prompts.map("remoteRepo")))
    }

    fun importConfigFromRepo(project: Project, model: AiTestSettingsModel): String {
        return importConfigFromRepo(
            project,
            BitbucketPromptRepoConfig(
                enabled = model.bitbucketPromptRepoEnabled,
                repoUrl = model.bitbucketPromptRepoUrl,
                branch = model.bitbucketPromptRepoBranch,
                token = model.bitbucketPromptRepoToken,
                username = model.bitbucketPromptRepoUsername,
                password = model.bitbucketPromptRepoPassword,
                configImportPath = model.bitbucketConfigImportPath
            )
        )
    }

    private fun importConfigFromRepo(project: Project, remoteRepo: BitbucketPromptRepoConfig): String {
        if (remoteRepo.repoUrl.isBlank()) {
            val message = "Cannot import repo config: prompts.remoteRepo.url is not configured."
            RuntimeLogStore.append("ERROR | Prompt Repo Import | $message")
            error(message)
        }

        val directBitbucketRawBaseUrl = parseDirectBitbucketRawBaseUrl(remoteRepo.repoUrl)
        if (remoteRepo.configImportPath.isNotBlank()) {
            val sourceText = bitbucketGet(project, remoteRepo.configImportPath, remoteRepo)
            val target = findOrCreateConfigFile()
            target.writeText(sourceText, Charsets.UTF_8)
            RuntimeLogStore.append("INFO | Prompt Repo Import | Success provider=BITBUCKET path=${remoteRepo.configImportPath} target=${target.absolutePath}")
            return "${remoteRepo.repoUrl}@${remoteRepo.configImportPath}"
        }

        val repo = directBitbucketRawBaseUrl?.let {
            RepositoryRef(
                provider = GitHostingProviderType.BITBUCKET,
                host = "<direct-raw-base>",
                projectKey = "<direct-raw-base>",
                repoSlug = "<direct-raw-base>"
            )
        } ?: GitRemoteParser.parse(remoteRepo.repoUrl)
        val candidatePaths = listOf("config/ai-test.yaml", ".ai-test.yaml")
        RuntimeLogStore.append(
            "INFO | Prompt Repo Import | Start provider=${repo.provider} repo=${repo.host}/${repo.projectKey}/${repo.repoSlug} branch=${remoteRepo.branch} candidates=${candidatePaths.joinToString(",")}" 
        )

        var lastError: Throwable? = null
        for (configPath in candidatePaths) {
            val sourceText = runCatching {
                when (repo.provider) {
                    GitHostingProviderType.BITBUCKET -> if (directBitbucketRawBaseUrl != null) {
                        loadBitbucketPromptRawFromBaseUrl(project, directBitbucketRawBaseUrl, configPath, remoteRepo.branch, remoteRepo)
                    } else {
                        loadBitbucketPromptRaw(
                            project = project,
                            host = repo.host,
                            projectKey = repo.projectKey,
                            repoSlug = repo.repoSlug,
                            path = configPath,
                            branch = remoteRepo.branch,
                            config = remoteRepo
                        )
                    }
                    GitHostingProviderType.GITHUB -> loadGitHubPromptRaw(
                        host = repo.host,
                        owner = repo.projectKey,
                        repo = repo.repoSlug,
                        path = configPath,
                        branch = remoteRepo.branch,
                        token = remoteRepo.token
                    )
                    else -> error("Unsupported prompt repo provider: ${repo.provider}. Only Bitbucket and GitHub are supported.")
                }
            }.onFailure { ex ->
                lastError = ex
                RuntimeLogStore.append("WARN | Prompt Repo Import | Miss provider=${repo.provider} path=$configPath reason=${ex.message ?: ex}")
            }.getOrNull()

            if (sourceText != null) {
                val target = findOrCreateConfigFile()
                target.writeText(sourceText, Charsets.UTF_8)
                RuntimeLogStore.append("INFO | Prompt Repo Import | Success provider=${repo.provider} path=$configPath target=${target.absolutePath}")
                return "${remoteRepo.repoUrl}@$configPath"
            }
        }

        val baseMessage = "Cannot import repo config. Provider=${repo.provider}. Tried: ${candidatePaths.joinToString(", ")}"
        val detail = lastError?.message?.takeIf { it.isNotBlank() }
        val finalMessage = if (detail != null) "$baseMessage. Last error: $detail" else baseMessage
        RuntimeLogStore.append("ERROR | Prompt Repo Import | $finalMessage")
        error(finalMessage)
    }

    fun checkBitbucketPromptUpdates(project: Project): PromptUpdateStatus {
        val model = loadSettingsModel(project)
        if (!model.bitbucketPromptRepoEnabled || model.bitbucketPromptRepoUrl.isBlank()) {
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
                password = model.bitbucketPromptRepoPassword,
                configImportPath = model.bitbucketConfigImportPath
            )
            RuntimeLogStore.append("INFO | Prompt Repo | Update check start repoUrl=${remoteConfig.repoUrl} branch=${remoteConfig.branch}")
            validateBitbucketPromptRepoConnection(project, remoteConfig)
            val remoteEntries = fetchBitbucketGlobalPromptEntries(project, remoteConfig, strict = true)
            val visibleRemoteEntries = remoteEntries.filterNot {
                isPromptGloballySuppressed(it.category, it.cacheKey, model.suppressedGlobalPrompts)
            }
            val remoteGlobalKeys = visibleRemoteEntries
                .map { it.cacheKey }
                .toSet()
            val cachedGlobalKeys = readCachedGlobalPromptKeys(project)
            val hasUpdates = remoteGlobalKeys != cachedGlobalKeys
            val addedPromptKeys = (remoteGlobalKeys - cachedGlobalKeys).sorted()
            if (addedPromptKeys.isNotEmpty()) {
                RuntimeLogStore.append("INFO | Prompt Repo | Added prompts: ${addedPromptKeys.joinToString()}")
            }
            RuntimeLogStore.append("INFO | Prompt Repo | Update check result remoteCount=${remoteGlobalKeys.size} cachedCount=${cachedGlobalKeys.size} hasUpdates=$hasUpdates")
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
                    if (remoteEntries.isEmpty()) {
                        "Prompt repo is reachable, but no prompt markdown files were found under prompt(s)/ directories."
                    } else {
                        val latestUpdatedAt = remoteEntries.maxOfOrNull { it.updatedAt }
                        "Prompt cache is up to date. Latest remote update: ${latestUpdatedAt ?: Instant.EPOCH}"
                    }
                }
            )
        }.getOrElse { ex ->
            RuntimeLogStore.append("ERROR | Prompt Repo | Update check failed: ${ex.message ?: ex}")
            PromptUpdateStatus(
                configured = true,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = ex.message ?: ex.toString(),
                error = true
            )
        }
    }

    fun pullBitbucketPromptUpdates(project: Project): PromptUpdateStatus {
        val root = readRootMap(project)
        val prompts = root["prompts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val remoteConfig = parseBitbucketPromptRepoConfig(prompts.map("remoteRepo"))
        if (!remoteConfig.enabled || remoteConfig.repoUrl.isBlank()) {
            return PromptUpdateStatus(
                configured = false,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = "Bitbucket prompt repo URL is not configured."
            )
        }

        return runCatching {
            RuntimeLogStore.append("INFO | Prompt Repo | Pull start repoUrl=${remoteConfig.repoUrl} branch=${remoteConfig.branch}")
            validateBitbucketPromptRepoConnection(project, remoteConfig)
            val remoteEntries = fetchBitbucketGlobalPromptEntries(project, remoteConfig, strict = true)
            writeGlobalPromptCache(project, root, remoteEntries)

            val suppressedGlobalPrompts = parseSuppressedGlobalPrompts(prompts)
            val visibleRemoteEntries = remoteEntries.filterNot {
                isPromptGloballySuppressed(it.category, it.cacheKey, suppressedGlobalPrompts)
            }
            val remoteGlobalKeys = visibleRemoteEntries.map { it.cacheKey }.toSet()
            val cachedGlobalKeys = readCachedGlobalPromptKeys(project)
            RuntimeLogStore.append(
                "INFO | Prompt Repo | Pull completed remoteCount=${remoteGlobalKeys.size} cachedCount=${cachedGlobalKeys.size}"
            )
            PromptUpdateStatus(
                configured = true,
                remoteCount = remoteGlobalKeys.size,
                cachedCount = cachedGlobalKeys.size,
                hasUpdates = false,
                message = if (remoteEntries.isEmpty()) {
                    "Prompt repo is reachable, but no prompt markdown files were found under prompt(s)/ directories."
                } else {
                    "Prompt cache updated from remote repo. Latest remote update: ${remoteEntries.maxOfOrNull { it.updatedAt } ?: Instant.EPOCH}"
                }
            )
        }.getOrElse { ex ->
            RuntimeLogStore.append("ERROR | Prompt Repo | Pull failed: ${ex.message ?: ex}")
            PromptUpdateStatus(
                configured = true,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = ex.message ?: ex.toString(),
                error = true
            )
        }
    }

    fun checkBitbucketSkillUpdates(project: Project): PromptUpdateStatus {
        val model = loadSettingsModel(project)
        val remoteConfig = BitbucketPromptRepoConfig(
            enabled = model.bitbucketPromptRepoEnabled,
            repoUrl = model.bitbucketPromptRepoUrl,
            branch = model.bitbucketPromptRepoBranch,
            token = model.bitbucketPromptRepoToken,
            username = model.bitbucketPromptRepoUsername,
            password = model.bitbucketPromptRepoPassword,
            configImportPath = model.bitbucketConfigImportPath
        )
        if (remoteConfig.repoUrl.isBlank()) {
            return PromptUpdateStatus(
                configured = false,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = "Skill repo URL is not configured."
            )
        }
        return runCatching {
            validateBitbucketPromptRepoConnection(project, remoteConfig)
            val remoteEntries = fetchBitbucketSkillEntries(project, remoteConfig)
            val remoteKeys = remoteEntries.map { it.cacheKey }.toSet()
            val cachedKeys = readCachedGlobalSkillKeys(project)
            PromptUpdateStatus(
                configured = true,
                remoteCount = remoteKeys.size,
                cachedCount = cachedKeys.size,
                hasUpdates = remoteKeys != cachedKeys,
                message = if (remoteEntries.isEmpty()) {
                    "Skill repo is reachable, but no skill markdown files were found under skill(s)/ directories."
                } else {
                    "Found ${remoteEntries.size} skill(s) in remote repo. Latest update: ${remoteEntries.maxOfOrNull { it.updatedAt } ?: Instant.EPOCH}"
                }
            )
        }.getOrElse { ex ->
            PromptUpdateStatus(
                configured = true,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = ex.message ?: ex.toString(),
                error = true
            )
        }
    }

    fun pullBitbucketSkillUpdates(project: Project): PromptUpdateStatus {
        val root = readRootMap(project)
        val model = loadSettingsModel(project)
        val remoteConfig = BitbucketPromptRepoConfig(
            enabled = model.bitbucketPromptRepoEnabled,
            repoUrl = model.bitbucketPromptRepoUrl,
            branch = model.bitbucketPromptRepoBranch,
            token = model.bitbucketPromptRepoToken,
            username = model.bitbucketPromptRepoUsername,
            password = model.bitbucketPromptRepoPassword,
            configImportPath = model.bitbucketConfigImportPath
        )
        if (remoteConfig.repoUrl.isBlank()) {
            return PromptUpdateStatus(
                configured = false,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = "Skill repo URL is not configured."
            )
        }
        return runCatching {
            RuntimeLogStore.append("INFO | Skill Repo | Pull start repoUrl=${remoteConfig.repoUrl} branch=${remoteConfig.branch}")
            validateBitbucketPromptRepoConnection(project, remoteConfig)
            val remoteEntries = fetchBitbucketSkillEntries(project, remoteConfig, strict = true)
            writeGlobalSkillCache(project, root, remoteEntries)
            writeSkillMarkdownFiles(loadSettingsModel(project))
            val remoteKeys = remoteEntries.map { it.cacheKey }.toSet()
            val cachedKeys = readCachedGlobalSkillKeys(project)
            RuntimeLogStore.append("INFO | Skill Repo | Pull completed remoteCount=${remoteKeys.size} cachedCount=${cachedKeys.size}")
            PromptUpdateStatus(
                configured = true,
                remoteCount = remoteKeys.size,
                cachedCount = cachedKeys.size,
                hasUpdates = false,
                message = if (remoteEntries.isEmpty()) {
                    "Skill repo is reachable, but no skill markdown files were found under skill(s)/ directories."
                } else {
                    "Skill cache updated from remote repo. Latest remote update: ${remoteEntries.maxOfOrNull { it.updatedAt } ?: Instant.EPOCH}"
                }
            )
        }.getOrElse { ex ->
            RuntimeLogStore.append("ERROR | Skill Repo | Pull failed: ${ex.message ?: ex}")
            PromptUpdateStatus(
                configured = true,
                remoteCount = 0,
                cachedCount = 0,
                hasUpdates = false,
                message = ex.message ?: ex.toString(),
                error = true
            )
        }
    }

    fun checkBitbucketHardcodedPath(project: Project, rawUrl: String): String {
        val target = rawUrl.trim()
        if (target.isBlank()) error("Hardcoded Bitbucket path is empty.")
        val model = loadSettingsModel(project)
        val config = BitbucketPromptRepoConfig(
            enabled = model.bitbucketPromptRepoEnabled,
            repoUrl = model.bitbucketPromptRepoUrl,
            branch = model.bitbucketPromptRepoBranch,
            token = model.bitbucketPromptRepoToken,
            username = model.bitbucketPromptRepoUsername,
            password = model.bitbucketPromptRepoPassword,
            configImportPath = model.bitbucketConfigImportPath
        )
        val body = bitbucketGet(project, target, config)
        return body.take(500)
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
        val sonarQube = root["sonarQube"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val remoteRepo = prompts.map("remoteRepo")
        val suppressedGlobalPrompts = parseSuppressedGlobalPrompts(prompts)
        val promptGeneration = prompts["generation"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

        return AiTestSettingsModel(
            llmProvider = llm.string("provider").ifBlank { "openai-compatible" },
            llmModel = llm.string("model"),
            llmEndpoint = llm.string("endpoint"),
            llmTimeoutSeconds = llm.string("timeoutSeconds").ifBlank { "60" },
            llmApiKey = llm.string("apiKey"),
            llmApiKeyEnv = llm.string("apiKeyEnv"),
            httpDisableTlsVerification = http?.get("disableTlsVerification") as? Boolean ?: false,
            showLogTab = ui["showLogTab"] as? Boolean ?: true,
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
                        AiPromptDefaults.GENERATION_WRAPPER,
                        includeDefault = false
                    ),
                    suppressedGlobalPrompts
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
                    AiPromptDefaults.COMMIT_MESSAGE,
                    includeDefault = false
                    ),
                    suppressedGlobalPrompts
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
                    AiPromptDefaults.BRANCH_DIFF_SUMMARY,
                    includeDefault = false
                    ),
                    suppressedGlobalPrompts
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
                        AiPromptDefaults.CODE_GENERATE,
                        includeDefault = false
                    ),
                    suppressedGlobalPrompts
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
                        AiPromptDefaults.CODE_REVIEW,
                        includeDefault = false
                    ),
                    suppressedGlobalPrompts
                )
            ),
            bitbucketPromptRepoEnabled = remoteRepo["enabled"] as? Boolean ?: false,
            bitbucketPromptRepoUrl = remoteRepo.string("url"),
            bitbucketPromptRepoBranch = remoteRepo.string("branch").ifBlank { "main" },
            bitbucketPromptRepoToken = remoteRepo.string("token"),
            bitbucketPromptRepoUsername = remoteRepo.string("username"),
            bitbucketPromptRepoPassword = remoteRepo.string("password"),
            bitbucketConfigImportPath = remoteRepo.string("configImportPath"),
            sonarQubeServerUrl = sonarQube.string("serverUrl"),
            sonarQubeProjectKey = sonarQube.string("projectKey"),
            sonarQubeToken = sonarQube.string("token"),
            sonarQubeTokenEnv = sonarQube.string("tokenEnv"),
            sonarQubeUsername = sonarQube.string("username"),
            sonarQubePassword = sonarQube.string("password"),
            sonarQubePasswordEnv = sonarQube.string("passwordEnv"),
            sonarQubeTargetCoverage = sonarQube.string("targetCoverage").ifBlank { "80" },
            sonarQubeMaxFiles = sonarQube.string("maxFiles").ifBlank { "5" },
            sonarQubeMockEnabled = sonarQube["mockEnabled"] as? Boolean ?: false,
            suppressedGlobalPrompts = suppressedGlobalPrompts,
            skillProfilesYaml = dumpPromptProfilesYaml(
                parsePromptProfileItems(
                    (root["skills"] as? Map<*, *>)?.map("items") ?: emptyMap<Any?, Any?>(),
                    "",
                    includeDefault = false
                )
            ),
            suppressedGlobalSkills = parseSuppressedGlobalItems(root["skills"] as? Map<*, *>)
        )
    }

    fun saveSettingsModel(project: Project, model: AiTestSettingsModel) {
        val root = readRootMap(project).toMutableLinkedMap()
        root["llm"] = buildLlmMap(root["llm"] as? Map<*, *>, model)
        root["ui"] = buildUiMap(root["ui"] as? Map<*, *>, model)
        root["sonarQube"] = buildSonarQubeMap(model)
        root.remove("generation")
        root["prompts"] = buildPromptsMap(project, root["prompts"] as? Map<*, *>, model)
        root["skills"] = buildSkillsMap(model)
        writeRootMap(project, root)
        writeSkillMarkdownFiles(model)
    }

    fun saveLlmSettingsModel(project: Project, model: AiTestSettingsModel) {
        val root = readRootMap(project).toMutableLinkedMap()
        root["llm"] = buildLlmMap(root["llm"] as? Map<*, *>, model)
        root["ui"] = buildUiMap(root["ui"] as? Map<*, *>, model)
        writeRootMap(project, root)
    }

    fun loadSonarQubeConfig(project: Project): SonarQubeConfig = parseSonarQubeConfig(readRootMap(project))

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
        val sonarQube = parseSonarQubeConfig(root)

        return AiTestConfig(
            llm = llm,
            generation = generation,
            prompts = prompts,
            sonarQube = sonarQube
        )
    }

    private fun parsePromptOverrides(project: Project, root: Map<*, *>): PromptOverrides {
        val prompts = root["prompts"] as? Map<*, *> ?: return PromptOverrides()
        val remoteRepo = parseBitbucketPromptRepoConfig(prompts.map("remoteRepo"))
        val suppressedGlobalPrompts = parseSuppressedGlobalPrompts(prompts)
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
                    remoteRepoConfig = remoteRepo,
                    suppressedGlobalPrompts = suppressedGlobalPrompts
                ),
                commitMessage = parsePromptProfileSet(
                    profileMap = prompts.map("commitMessageProfiles"),
                    defaultTemplate = prompts.string("commitMessage").ifBlank { AiPromptDefaults.COMMIT_MESSAGE },
                    globalCategory = "commit",
                    project = project,
                    remoteRepoConfig = remoteRepo,
                    suppressedGlobalPrompts = suppressedGlobalPrompts
                ),
                branchDiffSummary = parsePromptProfileSet(
                    profileMap = prompts.map("branchDiffSummaryProfiles"),
                    defaultTemplate = prompts.string("branchDiffSummary").ifBlank { AiPromptDefaults.BRANCH_DIFF_SUMMARY },
                    globalCategory = "branchDiff",
                    project = project,
                    remoteRepoConfig = remoteRepo,
                    suppressedGlobalPrompts = suppressedGlobalPrompts
                ),
                codeGenerate = parsePromptProfileSet(
                    profileMap = prompts.map("codeGenerateProfiles"),
                    defaultTemplate = AiPromptDefaults.CODE_GENERATE,
                    globalCategory = "codeGenerate",
                    project = project,
                    remoteRepoConfig = remoteRepo,
                    suppressedGlobalPrompts = suppressedGlobalPrompts
                ),
                codeReview = parsePromptProfileSet(
                    profileMap = prompts.map("codeReviewProfiles"),
                    defaultTemplate = AiPromptDefaults.CODE_REVIEW,
                    globalCategory = "codeReview",
                    project = project,
                    remoteRepoConfig = remoteRepo,
                    suppressedGlobalPrompts = suppressedGlobalPrompts
                )
            )
        )
    }

    private fun parseSonarQubeConfig(root: Map<*, *>): SonarQubeConfig {
        val sonar = root["sonarQube"] as? Map<*, *> ?: return SonarQubeConfig()
        return SonarQubeConfig(
            serverUrl = sonar.string("serverUrl"),
            projectKey = sonar.string("projectKey"),
            token = sonar.string("token"),
            tokenEnv = sonar.string("tokenEnv"),
            username = sonar.string("username"),
            password = sonar.string("password"),
            passwordEnv = sonar.string("passwordEnv"),
            targetCoverage = sonar.double("targetCoverage") ?: 80.0,
            maxFiles = sonar.int("maxFiles") ?: 5,
            mockEnabled = sonar["mockEnabled"] as? Boolean ?: false
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

    private fun buildSonarQubeMap(model: AiTestSettingsModel): MutableMap<String, Any> = linkedMapOf<String, Any>().apply {
        put("serverUrl", model.sonarQubeServerUrl)
        put("projectKey", model.sonarQubeProjectKey)
        put("token", model.sonarQubeToken)
        put("tokenEnv", model.sonarQubeTokenEnv)
        put("username", model.sonarQubeUsername)
        put("password", model.sonarQubePassword)
        put("passwordEnv", model.sonarQubePasswordEnv)
        put("targetCoverage", model.sonarQubeTargetCoverage.toDoubleOrNull() ?: 80.0)
        put("maxFiles", model.sonarQubeMaxFiles.toIntOrNull() ?: 5)
        put("mockEnabled", model.sonarQubeMockEnabled)
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
                        model.generationPromptWrapper,
                        includeDefault = false
                    ),
                    model.suppressedGlobalPrompts
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
                        model.commitPrompt,
                        includeDefault = false
                    ),
                    model.suppressedGlobalPrompts
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
                        model.branchDiffPrompt,
                        includeDefault = false
                    ),
                    model.suppressedGlobalPrompts
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
                        AiPromptDefaults.CODE_GENERATE,
                        includeDefault = false
                    ),
                    model.suppressedGlobalPrompts
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
                        AiPromptDefaults.CODE_REVIEW,
                        includeDefault = false
                    ),
                    model.suppressedGlobalPrompts
                )
            ),
            defaultTemplate = AiPromptDefaults.CODE_REVIEW
        )
        prompts["suppressedGlobalPrompts"] = model.suppressedGlobalPrompts.sorted()
        prompts["remoteRepo"] = linkedMapOf<String, Any>(
            "enabled" to model.bitbucketPromptRepoEnabled,
            "url" to model.bitbucketPromptRepoUrl,
            "branch" to model.bitbucketPromptRepoBranch,
            "token" to model.bitbucketPromptRepoToken,
            "username" to model.bitbucketPromptRepoUsername,
            "password" to model.bitbucketPromptRepoPassword,
            "configImportPath" to model.bitbucketConfigImportPath
        )
        return prompts
    }

    private fun buildSkillsMap(model: AiTestSettingsModel): Map<String, Any> {
        val items = parsePromptProfileItems(
            Yaml().load<Any?>(model.skillProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>(),
            "",
            includeDefault = false
        )
        return linkedMapOf(
            "selected" to (if (items.isNotEmpty()) items.keys.first() else "default"),
            "items" to items,
            "suppressed" to model.suppressedGlobalSkills.sorted()
        )
    }

    fun skillsDir(): File = File(configHomeDir(), "skills").also { it.mkdirs() }

    fun loadLocalSkillFiles(): List<Pair<String, String>> {
        val dir = skillsDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            ?.mapNotNull { file ->
                val text = file.readText(Charsets.UTF_8).trim()
                if (text.isBlank()) return@mapNotNull null
                val name = extractSkillName(text) ?: file.nameWithoutExtension
                val body = extractSkillBody(text)
                name to body
            }
            .orEmpty()
    }

    private fun extractSkillName(content: String): String? {
        val match = Regex("""(?im)^name\s*:\s*(.+)$""").find(content)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun writeSkillMarkdownFiles(model: AiTestSettingsModel) {
        val items = parsePromptProfileItems(
            Yaml().load<Any?>(model.skillProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>(),
            "",
            includeDefault = false
        )
        val dir = skillsDir()
        dir.listFiles()?.forEach { if (it.isFile && it.extension == "md") it.delete() }
        items.forEach { (name, content) ->
            val displayName = name.removePrefix("global/").replace(Regex("\\s*\\[[^]]+]$"), "")
            val safeFileName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val body = extractSkillBody(content)
            val file = File(dir, "$safeFileName.md")
            buildSkillMarkdown(name, body).let { file.writeText(it, Charsets.UTF_8) }
        }
    }

    private fun extractSkillBody(content: String): String {
        val lines = content.trim().lines()
        val metadataKeys = setOf("name", "type", "time", "updatedAt", "pulledAt", "description")
        val bodyStart = lines.indexOfFirst { line ->
            val key = line.substringBefore(":").trim().lowercase()
            key !in metadataKeys && line.isNotBlank()
        }
        if (bodyStart < 0) return content
        return lines.drop(bodyStart).joinToString("\n").trim()
    }

    private fun buildSkillMarkdown(name: String, body: String): String {
        return buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $name")
            appendLine("---")
            appendLine()
            appendLine(body.trim())
        }
    }

    private fun writeGlobalPromptCache(project: Project, root: Map<String, Any?>, remoteEntries: List<GlobalPromptMeta>) {
        val mutableRoot = root.toMutableLinkedMap()
        val prompts = (mutableRoot["prompts"] as? Map<*, *>).toMutableLinkedMap()
        val suppressedGlobalPrompts = parseSuppressedGlobalPrompts(prompts)
        promptProfileTargets().forEach { target ->
            val profile = (prompts[target.profileKey] as? Map<*, *>).toMutableLinkedMap()
            val existingItems = (profile["items"] as? Map<*, *>).toMutableLinkedMap()
            val itemsWithoutOldGlobal = existingItems
                .filterKeys { !it.startsWith("global/") }
                .toMutableMap()

            remoteEntries
                .filter { it.category == target.category }
                .filterNot { isPromptGloballySuppressed(it.category, it.cacheKey, suppressedGlobalPrompts) }
                .sortedWith(compareByDescending<GlobalPromptMeta> { it.updatedAt }.thenBy { it.name })
                .forEach { meta ->
                    itemsWithoutOldGlobal[meta.cacheKey] = ensurePromptUpdateMetadata(meta)
                }

            if (itemsWithoutOldGlobal.isEmpty()) {
                itemsWithoutOldGlobal[PromptProfileSet.DEFAULT_NAME] = target.defaultTemplate
            } else if (!itemsWithoutOldGlobal.containsKey(PromptProfileSet.DEFAULT_NAME)) {
                itemsWithoutOldGlobal[PromptProfileSet.DEFAULT_NAME] = target.defaultTemplate
            }

            val selected = profile["selected"]?.toString()?.takeIf { it.isNotBlank() }
                ?: target.selectedFallback
            profile["selected"] = selected
            profile["items"] = itemsWithoutOldGlobal
            prompts[target.profileKey] = profile
        }
        mutableRoot["prompts"] = prompts
        writeRootMap(project, mutableRoot)
    }

    private fun promptProfileTargets(): List<PromptProfileTarget> = listOf(
        PromptProfileTarget("test", "generationProfiles", AiPromptDefaults.GENERATION_WRAPPER, PromptProfileSet.DEFAULT_NAME),
        PromptProfileTarget("commit", "commitMessageProfiles", AiPromptDefaults.COMMIT_MESSAGE, GLOBAL_DIFF_REVIEW_PROFILE),
        PromptProfileTarget("branchDiff", "branchDiffSummaryProfiles", AiPromptDefaults.BRANCH_DIFF_SUMMARY, GLOBAL_DIFF_REVIEW_PROFILE),
        PromptProfileTarget("codeGenerate", "codeGenerateProfiles", AiPromptDefaults.CODE_GENERATE, PromptProfileSet.DEFAULT_NAME),
        PromptProfileTarget("codeReview", "codeReviewProfiles", AiPromptDefaults.CODE_REVIEW, PromptProfileSet.DEFAULT_NAME)
    )

    private fun ensurePromptUpdateMetadata(meta: GlobalPromptMeta): String {
        val lines = meta.template.trim().lines().toMutableList()
        upsertMetadataLine(lines, "name", meta.name)
        upsertMetadataLine(lines, "type", meta.category)
        upsertMetadataLine(lines, "time", meta.updatedAt.toString())
        upsertMetadataLine(lines, "updatedAt", meta.updatedAt.toString())
        upsertMetadataLine(lines, "pulledAt", Instant.now().toString())
        return lines.joinToString("\n").trim()
    }

    private fun upsertMetadataLine(lines: MutableList<String>, key: String, value: String) {
        val index = lines.indexOfFirst { it.trimStart().startsWith("$key:", ignoreCase = true) }
        val line = "$key: $value"
        if (index >= 0) {
            lines[index] = line
        } else {
            lines.add(0, line)
        }
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
        items: Map<String, String>,
        suppressedGlobalPrompts: Collection<String> = emptyList()
    ): Map<String, String> {
        val globalPrompts = loadGlobalPrompts(project, remoteRepoConfig)[category].orEmpty()
            .filterKeys { !isPromptGloballySuppressed(category, it, suppressedGlobalPrompts) }
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
        val app = ApplicationManager.getApplication()
        val shouldFetchRemote = app == null || !app.isDispatchThread
        if (shouldFetchRemote) {
            entries += fetchBitbucketGlobalPromptEntries(project, remoteRepoConfig)
        }

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
                            meta.cacheKey
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
        remoteRepoConfig: BitbucketPromptRepoConfig,
        suppressedGlobalPrompts: Collection<String> = emptyList()
    ): PromptProfileSet {
        val items = applyGlobalProfiles(
            project,
            globalCategory,
            remoteRepoConfig,
            parsePromptProfileItems(profileMap.map("items"), defaultTemplate),
            suppressedGlobalPrompts
        )
        val selected = profileMap.string("selected").ifBlank { PromptProfileSet.DEFAULT_NAME }
        return PromptProfileSet(selected = selected, items = items)
    }

    private fun parsePromptProfileItems(
        map: Map<*, *>,
        defaultTemplate: String,
        includeDefault: Boolean = true
    ): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        map.forEach { (key, value) ->
            val name = key?.toString()?.trim().orEmpty()
            val template = value?.toString().orEmpty()
            if (name.isNotEmpty() && template.isNotBlank()) {
                parsed[name] = template
            }
        }
        if (includeDefault) {
            if (parsed.isEmpty()) {
                parsed[PromptProfileSet.DEFAULT_NAME] = defaultTemplate
            } else if (!parsed.containsKey(PromptProfileSet.DEFAULT_NAME)) {
                parsed[PromptProfileSet.DEFAULT_NAME] = defaultTemplate
            }
        }
        return parsed
    }

    private fun buildPromptProfileMap(selected: String, yamlText: String, defaultTemplate: String): Map<String, Any> {
        val parsedYaml = Yaml().load<Any?>(yamlText) as? Map<*, *>
        val items = parsePromptProfileItems(
            parsedYaml ?: emptyMap<Any?, Any?>(),
            defaultTemplate,
            includeDefault = false
        )
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
            password = map.string("password"),
            configImportPath = map.string("configImportPath")
        )
    }

    private fun fetchBitbucketGlobalPromptEntries(project: Project, config: BitbucketPromptRepoConfig, strict: Boolean = false): List<GlobalPromptMeta> {
        if (!config.enabled || config.repoUrl.isBlank()) return emptyList()
        val result = runCatching {
            val directBitbucketRawBaseUrl = parseDirectBitbucketRawBaseUrl(config.repoUrl)
            val repo = directBitbucketRawBaseUrl?.let {
                RepositoryRef(
                    provider = GitHostingProviderType.BITBUCKET,
                    host = "<direct-raw-base>",
                    projectKey = "<direct-raw-base>",
                    repoSlug = "<direct-raw-base>"
                )
            } ?: GitRemoteParser.parse(config.repoUrl)
            val filePaths = when (repo.provider) {
                GitHostingProviderType.BITBUCKET -> if (directBitbucketRawBaseUrl != null) {
                    loadBitbucketPromptFilePathsFromRawBaseUrl(project, directBitbucketRawBaseUrl, config.branch, config)
                } else {
                    loadBitbucketPromptFilePaths(project, repo.host, repo.projectKey, repo.repoSlug, config.branch, config)
                }
                GitHostingProviderType.GITHUB -> loadGitHubPromptFilePaths(repo.host, repo.projectKey, repo.repoSlug, config.branch, config.token)
                else -> emptyList()
            }
            filePaths.mapNotNull { path ->
                val content = when (repo.provider) {
                    GitHostingProviderType.BITBUCKET -> if (directBitbucketRawBaseUrl != null) {
                        loadBitbucketPromptRawFromBaseUrl(project, directBitbucketRawBaseUrl, path, config.branch, config)
                    } else {
                        loadBitbucketPromptRaw(project, repo.host, repo.projectKey, repo.repoSlug, path, config.branch, config)
                    }
                    GitHostingProviderType.GITHUB -> loadGitHubPromptRaw(repo.host, repo.projectKey, repo.repoSlug, path, config.branch, config.token)
                    else -> return@mapNotNull null
                }
                parseGlobalPromptMeta(path, content)
            }
        }
        if (strict) return result.getOrThrow()
        return result.getOrDefault(emptyList())
    }

    private fun validateBitbucketPromptRepoConnection(project: Project, config: BitbucketPromptRepoConfig) {
        val directBitbucketRawBaseUrl = parseDirectBitbucketRawBaseUrl(config.repoUrl)
        val repo = directBitbucketRawBaseUrl?.let {
            RepositoryRef(
                provider = GitHostingProviderType.BITBUCKET,
                host = "<direct-raw-base>",
                projectKey = "<direct-raw-base>",
                repoSlug = "<direct-raw-base>"
            )
        } ?: GitRemoteParser.parse(config.repoUrl)
        val filePaths = when (repo.provider) {
            GitHostingProviderType.BITBUCKET -> if (directBitbucketRawBaseUrl != null) {
                loadBitbucketPromptFilePathsFromRawBaseUrl(project, directBitbucketRawBaseUrl, config.branch, config)
            } else {
                loadBitbucketPromptFilePaths(project, repo.host, repo.projectKey, repo.repoSlug, config.branch, config)
            }
            GitHostingProviderType.GITHUB -> loadGitHubPromptFilePaths(repo.host, repo.projectKey, repo.repoSlug, config.branch, config.token)
            else -> emptyList()
        }

        val firstLevelDirs = filePaths
            .map { it.replace('\\', '/').substringBefore('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        RuntimeLogStore.append(
            "INFO | Prompt Repo | Reachable provider=${repo.provider} repo=${repo.host}/${repo.projectKey}/${repo.repoSlug} branch=${config.branch} firstLevelDirs=${if (firstLevelDirs.isEmpty()) "<none>" else firstLevelDirs.joinToString(",")}" 
        )
    }


    private fun parseGlobalPromptMeta(path: String, content: String): GlobalPromptMeta? {
        if (!isPromptMarkdownPath(path)) return null
        val text = content.trim()
        if (text.isBlank()) return null
        val nameMatch = Regex("(?im)^name\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val timeMatch = Regex("(?im)^time\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val updatedAtMatch = Regex("(?im)^updatedAt\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val typeMatch = Regex("(?im)^type\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()

        val category = resolveCategory(path, typeMatch) ?: "test"
        val name = nameMatch?.takeIf { it.isNotBlank() } ?: File(path).nameWithoutExtension
        val time = parseInstantOrNow(timeMatch ?: updatedAtMatch)
        return GlobalPromptMeta(
            category = category,
            name = name,
            updatedAt = time,
            template = text,
            sourcePriority = 2
        )
    }

    private fun parseInstantOrNow(raw: String?): Instant {
        if (raw.isNullOrBlank()) return Instant.now()
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

    private fun loadBitbucketPromptRawFromBaseUrl(
        project: Project,
        rawBaseUrl: String,
        path: String,
        branch: String,
        config: BitbucketPromptRepoConfig
    ): String {
        val normalizedBase = rawBaseUrl.trimEnd('/')
        val encodedPath = path.split("/").joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
        val encodedBranch = URLEncoder.encode("refs/heads/$branch", StandardCharsets.UTF_8)
        val url = "$normalizedBase/$encodedPath?at=$encodedBranch"
        return bitbucketGet(project, url, config)
    }

    private fun loadBitbucketPromptFilePathsFromRawBaseUrl(
        project: Project,
        rawBaseUrl: String,
        branch: String,
        config: BitbucketPromptRepoConfig
    ): List<String> {
        val normalizedBase = rawBaseUrl.trimEnd('/')
        val filesBase = normalizedBase.replace(Regex("/raw/?$"), "/files")
        val encodedBranch = URLEncoder.encode("refs/heads/$branch", StandardCharsets.UTF_8)
        val url = "$filesBase?at=$encodedBranch&limit=1000"
        val body = bitbucketGet(project, url, config)
        val values = json.parseToJsonElement(body).jsonObject["values"]?.jsonArray ?: return emptyList()
        return values.mapNotNull { it.jsonPrimitive.contentOrNull }
            .filter { isPromptMarkdownPath(it) }
    }

    private fun parseDirectBitbucketRawBaseUrl(repoUrl: String): String? {
        return null
    }

    private fun loadGitHubPromptRaw(
        host: String,
        owner: String,
        repo: String,
        path: String,
        branch: String,
        token: String
    ): String {
        val encodedPath = path.split("/").joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
        val encodedBranch = URLEncoder.encode(branch, StandardCharsets.UTF_8)
        val url = "https://api.$host/repos/$owner/$repo/contents/$encodedPath?ref=$encodedBranch"
        return githubGet(url, token)
    }

    private fun githubGet(url: String, token: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 60_000
        conn.readTimeout = 60_000
        val normalized = token.trim()
        if (normalized.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $normalized")
        }
        conn.setRequestProperty("Accept", "application/vnd.github.raw+json")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        if (code !in 200..299) {
            error("GitHub request failed ($code) for $url. Response: ${body.ifBlank { "<empty>" }}")
        }
        return body
    }

    private fun bitbucketGet(@Suppress("UNUSED_PARAMETER") project: Project, url: String, config: BitbucketPromptRepoConfig): String {
        val configuredCredentials = if (config.username.isNotBlank() && config.password.isNotBlank()) {
            listOf(BitbucketCredential("settings", config.username, config.password))
        } else {
            emptyList()
        }
        val gitCredentials = GitCredentialHelper.resolve(config.repoUrl)
            ?.let { listOf(BitbucketCredential("git-credential-helper", it.username, it.password)) }
            ?: emptyList()
        val credentials = configuredCredentials + gitCredentials
        return bitbucketGetWithCredentials(url, config.token, credentials)
    }

    private fun bitbucketGetWithCredentials(url: String, token: String, credentials: List<BitbucketCredential>): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 60_000
        conn.readTimeout = 60_000
        val normalized = token.trim()
        val authHeaderLog = if (normalized.isNotBlank()) {
            if (normalized.contains(":")) {
                val basic = Base64.getEncoder().encodeToString(normalized.toByteArray(Charsets.UTF_8))
                conn.setRequestProperty("Authorization", "Basic $basic")
                describeBasicTokenHeader(normalized)
            } else {
                conn.setRequestProperty("Authorization", "Bearer $normalized")
                "Authorization=Bearer token=${maskSecret(normalized)}"
            }
        } else if (credentials.isNotEmpty()) {
            val credential = credentials.first()
            val basicRaw = "${credential.username}:${credential.password}"
            val basic = Base64.getEncoder().encodeToString(basicRaw.toByteArray(Charsets.UTF_8))
            conn.setRequestProperty("Authorization", "Basic $basic")
            describeBasicCredentialHeader(credential)
        } else {
            "Authorization=<absent>"
        }
        val curlCommand = buildBitbucketCurlCommand(url, normalized, credentials)
        RuntimeLogStore.append(
            "INFO | Bitbucket API | Request method=GET url=$url headers[$authHeaderLog] credentialSources=${credentials.joinToString(",") { it.source }.ifBlank { "<none>" }} | curl=$curlCommand"
        )
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        if (code !in 200..299) {
            val response = body.ifBlank { "<empty>" }
            val authHint = if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                " Configure Token/PAT or Username + Password in the Bitbucket Prompt Repo settings, or make sure git credential helper has valid credentials for the repo URL."
            } else {
                ""
            }
            error("Bitbucket API request failed ($code) for $url.$authHint Response: $response")
        }
        return body
    }


    private fun buildBitbucketCurlCommand(url: String, token: String, credentials: List<BitbucketCredential>): String {
        val authorizationHeader = when {
            token.isNotBlank() && token.contains(":") -> {
                val username = token.substringBefore(':')
                "Authorization: Basic ${displayHeaderValue(username)}:***"
            }
            token.isNotBlank() -> "Authorization: Bearer ***"
            credentials.isNotEmpty() -> {
                val credential = credentials.first()
                "Authorization: Basic ${displayHeaderValue(credential.username)}:***"
            }
            else -> null
        }
        val authPart = authorizationHeader?.let { " -H " + shellQuote(it) }.orEmpty()
        return "curl -X GET " + shellQuote(url) + authPart
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun describeBasicTokenHeader(token: String): String {
        val separatorIndex = token.indexOf(':')
        val username = token.substring(0, separatorIndex)
        val password = token.substring(separatorIndex + 1)
        return "Authorization=Basic source=token username=${displayHeaderValue(username)} password=${maskSecret(password)}"
    }

    private fun describeBasicCredentialHeader(credential: BitbucketCredential): String {
        return "Authorization=Basic source=${credential.source} username=${displayHeaderValue(credential.username)} password=${maskSecret(credential.password)}"
    }

    private fun displayHeaderValue(value: String): String = value.ifBlank { "<blank>" }

    private fun maskSecret(value: String): String = if (value.isBlank()) "<blank>" else "*".repeat(value.length)

    private fun Map<*, *>?.string(key: String): String =
        this?.get(key)?.toString()?.trim().orEmpty()

    private fun Map<*, *>?.double(key: String): Double? = when (val value = this?.get(key)) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    private fun Map<*, *>?.int(key: String): Int? = when (val value = this?.get(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

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

    private fun loadGitHubPromptFilePaths(
        host: String,
        owner: String,
        repo: String,
        branch: String,
        token: String
    ): List<String> {
        val encodedBranch = URLEncoder.encode(branch, StandardCharsets.UTF_8)
        val url = "https://api.$host/repos/$owner/$repo/git/trees/$encodedBranch?recursive=1"
        val body = githubGet(url, token)
        val tree = json.parseToJsonElement(body).jsonObject["tree"]?.jsonArray ?: return emptyList()
        return tree.mapNotNull { node ->
            val obj = node.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val path = obj["path"]?.jsonPrimitive?.contentOrNull
            if (type == "blob" && path != null && isPromptMarkdownPath(path)) path else null
        }
    }

    private fun parseSuppressedGlobalPrompts(prompts: Map<*, *>): List<String> {
        return (prompts["suppressedGlobalPrompts"] as? List<*>)
            .orEmpty()
            .mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotBlank() } }
            .distinct()
    }

    private fun parseSuppressedGlobalItems(section: Map<*, *>?): List<String> {
        return (section?.get("suppressed") as? List<*>)
            .orEmpty()
            .mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotBlank() } }
            .distinct()
    }

    private fun isPromptGloballySuppressed(category: String, cacheKey: String, suppressed: Collection<String>): Boolean {
        if (suppressed.isEmpty()) return false
        val fullKey = "$category:$cacheKey"
        if (fullKey in suppressed) return true
        val displayName = cacheKey.removePrefix("global/").replace(Regex("\\s*\\[[^]]+]$"), "")
        return "$category:$displayName" in suppressed
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

    private fun isSkillMarkdownPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return (normalized.startsWith("skills/") || normalized.startsWith("skill/")) && normalized.endsWith(".md")
    }

    private fun fetchBitbucketSkillEntries(project: Project, config: BitbucketPromptRepoConfig, strict: Boolean = false): List<GlobalPromptMeta> {
        if (config.repoUrl.isBlank()) return emptyList()
        val result = runCatching {
            val directBitbucketRawBaseUrl = parseDirectBitbucketRawBaseUrl(config.repoUrl)
            val repo = directBitbucketRawBaseUrl?.let {
                RepositoryRef(
                    provider = GitHostingProviderType.BITBUCKET,
                    host = "<direct-raw-base>",
                    projectKey = "<direct-raw-base>",
                    repoSlug = "<direct-raw-base>"
                )
            } ?: GitRemoteParser.parse(config.repoUrl)
            val filePaths = when (repo.provider) {
                GitHostingProviderType.BITBUCKET -> if (directBitbucketRawBaseUrl != null) {
                    loadBitbucketSkillFilePathsFromRawBaseUrl(project, directBitbucketRawBaseUrl, config.branch, config)
                } else {
                    loadBitbucketSkillFilePaths(project, repo.host, repo.projectKey, repo.repoSlug, config.branch, config)
                }
                GitHostingProviderType.GITHUB -> loadGitHubSkillFilePaths(repo.host, repo.projectKey, repo.repoSlug, config.branch, config.token)
                else -> emptyList()
            }
            filePaths.mapNotNull { path ->
                val content = when (repo.provider) {
                    GitHostingProviderType.BITBUCKET -> if (directBitbucketRawBaseUrl != null) {
                        loadBitbucketPromptRawFromBaseUrl(project, directBitbucketRawBaseUrl, path, config.branch, config)
                    } else {
                        loadBitbucketPromptRaw(project, repo.host, repo.projectKey, repo.repoSlug, path, config.branch, config)
                    }
                    GitHostingProviderType.GITHUB -> loadGitHubPromptRaw(repo.host, repo.projectKey, repo.repoSlug, path, config.branch, config.token)
                    else -> return@mapNotNull null
                }
                parseGlobalSkillMeta(path, content)
            }
        }
        if (strict) return result.getOrThrow()
        return result.getOrDefault(emptyList())
    }

    private fun parseGlobalSkillMeta(path: String, content: String): GlobalPromptMeta? {
        if (!isSkillMarkdownPath(path)) return null
        val text = content.trim()
        if (text.isBlank()) return null
        val nameMatch = Regex("(?im)^name\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val timeMatch = Regex("(?im)^time\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val updatedAtMatch = Regex("(?im)^updatedAt\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        val name = nameMatch?.takeIf { it.isNotBlank() } ?: File(path).nameWithoutExtension
        val time = parseInstantOrNow(timeMatch ?: updatedAtMatch)
        return GlobalPromptMeta(
            category = "skill",
            name = name,
            updatedAt = time,
            template = text,
            sourcePriority = 2
        )
    }

    private fun loadBitbucketSkillFilePaths(
        project: Project, host: String, projectKey: String, repoSlug: String,
        branch: String, config: BitbucketPromptRepoConfig
    ): List<String> {
        val encodedBranch = URLEncoder.encode("refs/heads/$branch", StandardCharsets.UTF_8)
        val url = "https://$host/rest/api/1.0/projects/$projectKey/repos/$repoSlug/files?at=$encodedBranch&limit=1000"
        val body = bitbucketGet(project, url, config)
        val values = json.parseToJsonElement(body).jsonObject["values"]?.jsonArray ?: return emptyList()
        return values.mapNotNull { it.jsonPrimitive.contentOrNull }
            .filter { isSkillMarkdownPath(it) }
    }

    private fun loadBitbucketSkillFilePathsFromRawBaseUrl(
        project: Project, rawBaseUrl: String, branch: String, config: BitbucketPromptRepoConfig
    ): List<String> {
        val normalizedBase = rawBaseUrl.trimEnd('/')
        val filesBase = normalizedBase.replace(Regex("/raw/?$"), "/files")
        val encodedBranch = URLEncoder.encode("refs/heads/$branch", StandardCharsets.UTF_8)
        val url = "$filesBase?at=$encodedBranch&limit=1000"
        val body = bitbucketGet(project, url, config)
        val values = json.parseToJsonElement(body).jsonObject["values"]?.jsonArray ?: return emptyList()
        return values.mapNotNull { it.jsonPrimitive.contentOrNull }
            .filter { isSkillMarkdownPath(it) }
    }

    private fun loadGitHubSkillFilePaths(
        host: String, owner: String, repo: String, branch: String, token: String
    ): List<String> {
        val encodedBranch = URLEncoder.encode(branch, StandardCharsets.UTF_8)
        val url = "https://api.$host/repos/$owner/$repo/git/trees/$encodedBranch?recursive=1"
        val body = githubGet(url, token)
        val tree = json.parseToJsonElement(body).jsonObject["tree"]?.jsonArray ?: return emptyList()
        return tree.mapNotNull { node ->
            val obj = node.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val path = obj["path"]?.jsonPrimitive?.contentOrNull
            if (type == "blob" && path != null && isSkillMarkdownPath(path)) path else null
        }
    }

    private fun writeGlobalSkillCache(project: Project, root: Map<String, Any?>, remoteEntries: List<GlobalPromptMeta>) {
        val mutableRoot = root.toMutableLinkedMap()
        val skills = (mutableRoot["skills"] as? Map<*, *>).toMutableLinkedMap()
        val suppressedGlobalSkills = parseSuppressedGlobalItems(skills)
        val existingItems = (skills["items"] as? Map<*, *>).toMutableLinkedMap()
        val itemsWithoutOldGlobal = existingItems
            .filterKeys { !it.toString().startsWith("global/") }
            .toMutableMap()

        remoteEntries
            .filterNot { it.cacheKey in suppressedGlobalSkills }
            .sortedWith(compareByDescending<GlobalPromptMeta> { it.updatedAt }.thenBy { it.name })
            .forEach { meta ->
                itemsWithoutOldGlobal[meta.cacheKey] = ensurePromptUpdateMetadata(meta)
            }

        skills["selected"] = skills["selected"]?.toString()?.takeIf { it.isNotBlank() } ?: "default"
        skills["items"] = itemsWithoutOldGlobal
        skills["suppressed"] = suppressedGlobalSkills
        mutableRoot["skills"] = skills
        writeRootMap(project, mutableRoot)
    }

    private fun readCachedGlobalSkillKeys(project: Project): Set<String> {
        val root = readRootMap(project)
        val skills = root["skills"] as? Map<*, *> ?: return emptySet()
        val items = skills["items"] as? Map<*, *> ?: return emptySet()
        return items.keys.mapNotNull { it?.toString() }.filter { it.startsWith("global/") }.toSet()
    }
}
