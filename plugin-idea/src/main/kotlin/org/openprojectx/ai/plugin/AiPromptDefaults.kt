package org.openprojectx.ai.plugin

import org.openprojectx.ai.plugin.core.PromptBuilder

object AiPromptDefaults {
    const val COMMIT_MESSAGE = """
        You are an expert Git commit message generator.

        Based on the git diff below, generate a concise and high-quality commit message.

        Requirements:
        - Use Conventional Commits style when appropriate
        - Output only the commit message
        - First line must be <= 72 characters if possible
        - Focus on intent and user-visible/codebase-visible change
        - Do not include markdown fences
        - Do not explain your answer

        Preferred format:
        <type>: <short summary>

        Optional body:
        - bullet points only if really needed

        Git diff:
        {{diff}}
    """

    const val PULL_REQUEST = """
        You are an expert software engineer creating a pull request.

        Generate:
        1. A concise PR title
        2. A clear PR description

        Requirements:
        - Output valid JSON only
        - JSON shape:
          {
            "title": "...",
            "description": "..."
          }
        - Title should be short and actionable
        - Description should explain:
          - what changed
          - why it changed
          - important implementation notes
        - Use markdown in description
        - Do not include code fences

        Source branch: {{sourceBranch}}
        Target branch: {{targetBranch}}

        Git diff:
        {{diff}}
    """

    val GENERATION_WRAPPER: String = PromptBuilder.DEFAULT_WRAPPER_TEMPLATE
    val GENERATION_REST_ASSURED: String = PromptBuilder.DEFAULT_REST_ASSURED_RULES
    val GENERATION_KARATE: String = PromptBuilder.DEFAULT_KARATE_RULES

    fun render(template: String, variables: Map<String, String>): String {
        var result = template.trimIndent()
        variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
