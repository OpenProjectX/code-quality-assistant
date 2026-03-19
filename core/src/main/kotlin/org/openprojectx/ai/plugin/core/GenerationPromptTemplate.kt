package org.openprojectx.ai.plugin.core

data class GenerationPromptTemplate(
    val wrapper: String = PromptBuilder.DEFAULT_WRAPPER_TEMPLATE,
    val restAssuredRules: String = PromptBuilder.DEFAULT_REST_ASSURED_RULES,
    val karateRules: String = PromptBuilder.DEFAULT_KARATE_RULES
)
