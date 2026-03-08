package org.openprojectx.ai.plugin.core


data class GenerationRequest(
    val contractText: String,
    val framework: Framework,
    val baseUrl: String? = null,
    val location: String? = null,
    val packageName: String? = null,
    val className: String,
    val outputNotes: String?
)