package org.openprojectx.ai.plugin

import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.core.GenerationPromptTemplate
import org.openprojectx.ai.plugin.llm.LlmSettings

data class AiTestConfig(
    val llm: LlmSettings,
    val generation: GenerationConfig = GenerationConfig(),
    val prompts: PromptOverrides = PromptOverrides()
)

data class PromptOverrides(
    val generation: GenerationPromptTemplate = GenerationPromptTemplate(),
    val commitMessage: String = AiPromptDefaults.COMMIT_MESSAGE,
    val pullRequest: String = AiPromptDefaults.PULL_REQUEST,
    val branchDiffSummary: String = AiPromptDefaults.BRANCH_DIFF_SUMMARY,
    val profiles: PromptProfiles = PromptProfiles()
)

data class PromptProfiles(
    val generation: PromptProfileSet = PromptProfileSet.default(
        defaultTemplate = AiPromptDefaults.GENERATION_WRAPPER
    ),
    val commitMessage: PromptProfileSet = PromptProfileSet.default(
        defaultTemplate = AiPromptDefaults.COMMIT_MESSAGE
    ),
    val branchDiffSummary: PromptProfileSet = PromptProfileSet.default(
        defaultTemplate = AiPromptDefaults.BRANCH_DIFF_SUMMARY
    ),
    val codeGenerate: PromptProfileSet = PromptProfileSet.default(
        defaultTemplate = AiPromptDefaults.CODE_GENERATE
    ),
    val codeReview: PromptProfileSet = PromptProfileSet.default(
        defaultTemplate = AiPromptDefaults.CODE_REVIEW
    )
)

data class PromptProfileSet(
    val selected: String = DEFAULT_NAME,
    val items: Map<String, String> = linkedMapOf(DEFAULT_NAME to "")
) {
    companion object {
        const val DEFAULT_NAME = "default"

        fun default(defaultTemplate: String): PromptProfileSet = PromptProfileSet(
            selected = DEFAULT_NAME,
            items = linkedMapOf(DEFAULT_NAME to defaultTemplate)
        )
    }
}

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
