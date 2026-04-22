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
        - If current branch name contains a JIRA key like ABC-123, prefix commit subject with "ABC-123: "
        - Focus on intent and user-visible/codebase-visible change
        - Do not include markdown fences
        - Do not explain your answer

        Preferred format:
        <type>: <short summary>

        Optional body:
        - bullet points only if really needed

        Current branch:
        {{branchName}}

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



    const val BRANCH_DIFF_SUMMARY = """
        You are an expert software reviewer.

        Summarize the key differences between two git branches based on the diff.

        Requirements:
        - Output in concise markdown
        - Start with a 1-2 sentence overall summary
        - Then provide 3-6 bullet points for important changes
        - Focus on behavior, architecture, and risk areas
        - Do not include code fences

        Source branch: {{sourceBranch}}
        Target branch: {{targetBranch}}

        Git diff:
        {{diff}}
    """

    const val CODE_GENERATE = """
        You are a senior software engineer.

        Generate improved or new code based on the selected snippet.

        Requirements:
        - Return only the final code/result content
        - Keep style consistent with the input code
        - Prefer minimal, safe, and compilable changes
        - Do not include extra explanations unless required by the prompt

        Selected code:
        {{selectedCode}}

        Extra requirements from user:
        {{extraRequirements}}
    """

    const val CODE_REVIEW = """
        You are a senior code reviewer.

        Review the selected code and provide actionable feedback.

        Requirements:
        - Focus on correctness, readability, maintainability, and potential bugs
        - Use concise markdown bullet points
        - Include suggested fixes when useful
        - Do not include unrelated commentary

        Selected code:
        {{selectedCode}}

        Extra requirements from user:
        {{extraRequirements}}
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
