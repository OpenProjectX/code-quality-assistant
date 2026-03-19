package org.openprojectx.ai.plugin

import org.openprojectx.ai.plugin.core.Framework

data class AiTestSettingsModel(
    val llmProvider: String = "openai-compatible",
    val llmModel: String = "",
    val llmEndpoint: String = "",
    val llmTimeoutSeconds: String = "60",
    val llmApiKey: String = "",
    val llmApiKeyEnv: String = "",
    val llmTemplateEnabled: Boolean = false,
    val llmTemplateMethod: String = "POST",
    val llmTemplateUrl: String = "",
    val llmTemplateHeaders: String = "",
    val llmTemplateBody: String = "",
    val llmTemplateResponsePath: String = "",
    val loginEnabled: Boolean = false,
    val loginMethod: String = "POST",
    val loginUrl: String = "",
    val loginHeaders: String = "",
    val loginBody: String = "",
    val loginResponsePath: String = "",
    val defaultFramework: Framework = AiTestDefaults.DEFAULT_FRAMEWORK,
    val defaultClassName: String = AiTestDefaults.DEFAULT_CLASS_NAME,
    val defaultBaseUrl: String = AiTestDefaults.DEFAULT_BASE_URL,
    val defaultNotes: String = AiTestDefaults.DEFAULT_NOTES,
    val commonLocation: String = "",
    val restAssuredLocation: String = "",
    val restAssuredPackageName: String = "",
    val karateLocation: String = "",
    val generationPromptWrapper: String = AiPromptDefaults.GENERATION_WRAPPER,
    val generationPromptRestAssured: String = AiPromptDefaults.GENERATION_REST_ASSURED,
    val generationPromptKarate: String = AiPromptDefaults.GENERATION_KARATE,
    val commitPrompt: String = AiPromptDefaults.COMMIT_MESSAGE,
    val pullRequestPrompt: String = AiPromptDefaults.PULL_REQUEST
)
