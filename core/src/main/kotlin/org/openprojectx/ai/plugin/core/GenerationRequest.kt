package org.openprojectx.ai.plugin.core


data class GenerationRequest(
    val contractText: String,
    val framework: Framework,
    val baseUrl: String?,          // optional hint
    val packageName: String,
    val className: String,
    val outputNotes: String?       // optional extra user instruction
)