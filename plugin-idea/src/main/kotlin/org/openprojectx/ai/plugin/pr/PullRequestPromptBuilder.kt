package org.openprojectx.ai.plugin.pr

import kotlinx.serialization.Serializable

object PullRequestPromptBuilder {

    fun build(template: String, diff: String, sourceBranch: String, targetBranch: String): String {
        return org.openprojectx.ai.plugin.AiPromptDefaults.render(
            template,
            mapOf(
                "sourceBranch" to sourceBranch,
                "targetBranch" to targetBranch,
                "diff" to diff
            )
        )
    }
}

@Serializable
data class GeneratedPullRequestContent(
    val title: String,
    val description: String
)
