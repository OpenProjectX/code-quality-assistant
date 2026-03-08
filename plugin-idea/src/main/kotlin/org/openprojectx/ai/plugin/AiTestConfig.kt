package org.openprojectx.ai.plugin

import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.llm.LlmSettings

data class AiTestConfig(
    val llm: LlmSettings,
    val generation: GenerationConfig = GenerationConfig()
)

data class GenerationConfig(
    val defaultFramework: Framework = AiTestDefaults.DEFAULT_FRAMEWORK,
    val defaultClassName: String = AiTestDefaults.DEFAULT_CLASS_NAME,
    val defaultBaseUrl: String = AiTestDefaults.DEFAULT_BASE_URL,
    val defaultNotes: String = AiTestDefaults.DEFAULT_NOTES,
    val common: CommonGenerationDefaults = CommonGenerationDefaults(),
    val frameworks: Map<Framework, FrameworkGenerationDefaults> = emptyMap()
) {
    fun defaultsFor(framework: Framework): ResolvedFrameworkDefaults {
        val frameworkDefaults = frameworks[framework] ?: FrameworkGenerationDefaults()
        return ResolvedFrameworkDefaults(
            location = frameworkDefaults.location ?: common.location ?: AiTestDefaults.defaultLocation(framework),
            packageName = frameworkDefaults.packageName ?: AiTestDefaults.defaultPackageName(framework)
        )
    }
}

data class CommonGenerationDefaults(
    val location: String? = null
)

data class FrameworkGenerationDefaults(
    val location: String? = null,
    val packageName: String? = null
)

data class ResolvedFrameworkDefaults(
    val location: String,
    val packageName: String?
)