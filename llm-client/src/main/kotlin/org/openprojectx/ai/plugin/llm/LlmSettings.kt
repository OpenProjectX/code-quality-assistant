package org.openprojectx.ai.plugin.llm

data class LlmSettings(
    val endpoint: String,   // e.g. https://api.openai.com/v1/chat/completions or an OpenAI-compatible gateway
    val apiKey: String,
    val model: String,
    val timeoutSeconds: Long = 60
)