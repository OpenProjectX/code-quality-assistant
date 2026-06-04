package org.openprojectx.ai.plugin

object PromptProfileResolver {

    fun resolve(profileSet: PromptProfileSet, fallbackTemplate: String): String {
        val selectedName = profileSet.selected.trim().ifBlank { PromptProfileSet.DEFAULT_NAME }
        val raw = profileSet.items[selectedName]
            ?.takeIf { it.isNotBlank() }
            ?: profileSet.items[PromptProfileSet.DEFAULT_NAME]
                ?.takeIf { it.isNotBlank() }
            ?: fallbackTemplate
        return LlmSettingsLoader.stripPromptMetadata(raw)
    }
}
