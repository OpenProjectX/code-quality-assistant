package org.openprojectx.ai.plugin.core


enum class ContractType {
    OPENAPI,
    JAVA
}

data class GenerationRequest(
    val contractText: String,
    val framework: Framework,
    val contractType: ContractType = ContractType.OPENAPI,
    val baseUrl: String? = null,
    val location: String? = null,
    val packageName: String? = null,
    val className: String,
    val outputNotes: String?,
    val dependentMethodSignatures: String = "",
    val environmentContext: String = ""
)
