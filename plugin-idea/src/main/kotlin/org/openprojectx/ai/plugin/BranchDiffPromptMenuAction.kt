package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DumbAware
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.vcs.log.VcsLogDataKeys
import git4idea.repo.GitRepositoryManager

class BranchDiffPromptMenuAction : ActionGroup("Analyze Branch Diff (Choose Prompt)", true), DumbAware {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val profiles = LlmSettingsLoader.loadConfig(project).prompts.profiles.branchDiffSummary.items
        if (profiles.isEmpty()) return emptyArray()

        return profiles.entries.map { (name, template) ->
            BranchDiffByPromptAction(name, template)
        }.toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

private class BranchDiffByPromptAction(
    private val promptName: String,
    private val promptTemplate: String
) : AnAction(promptName), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sourceBranch = resolveCurrentBranch(project)
        val targetRef = resolveTargetRef(e, sourceBranch)
        if (targetRef == null) {
            Notifications.warn(
                project,
                "Summarize Branch Diff",
                "Please select a branch or commit in VCS Log to compare with current branch $sourceBranch."
            )
            return
        }

        saveDefaultBranchDiffPromptProfile(project, promptName)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Summarizing Branch Diff", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Collecting branch diff for $sourceBranch vs $targetRef..."
                    val diff = GitDiffProvider.getDiffBetweenBranches(
                        project = project,
                        sourceBranch = sourceBranch,
                        targetBranch = targetRef
                    )
                    if (diff.isBlank()) {
                        Notifications.info(project, "Summarize Branch Diff", "No changes found between $sourceBranch and $targetRef.")
                        return
                    }

                    indicator.text = "Generating summary..."
                    val summary = AiBranchDiffSummaryService(project).generate(
                        sourceBranch = sourceBranch,
                        targetBranch = targetRef,
                        diff = diff,
                        templateOverride = promptTemplate
                    )

                    ApplicationManager.getApplication().invokeLater {
                        ContextBoxStateService.getInstance(project).recordBranchSummary(
                            targetBranch = targetRef,
                            sourceBranch = sourceBranch,
                            summary = summary
                        )
                        Notifications.info(project, "Branch Diff Summary", "Summary updated in AI Context Box > Branch Analysis.")
                    }
                } catch (ex: Exception) {
                    Notifications.error(project, "Summarize Branch Diff failed", ex.message ?: ex.toString())
                }
            }
        })
    }

    private fun saveDefaultBranchDiffPromptProfile(project: com.intellij.openapi.project.Project, selectedName: String) {
        val current = LlmSettingsLoader.loadSettingsModel(project)
        if (current.branchDiffPromptProfileDefault == selectedName) return
        LlmSettingsLoader.saveSettingsModel(
            project,
            current.copy(branchDiffPromptProfileDefault = selectedName)
        )
    }

    private fun resolveCurrentBranch(project: com.intellij.openapi.project.Project): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        return repo?.currentBranchName ?: "HEAD"
    }

    private fun resolveTargetRef(e: AnActionEvent, currentBranch: String): String? {
        val rawBranches = e.getData(VcsLogDataKeys.VCS_LOG_BRANCHES) as? Collection<*>
        if (rawBranches != null) {
            val branches = linkedSetOf<String>()
            for (rawBranch in rawBranches) {
                val name = extractBranchName(rawBranch)?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    branches.add(name)
                }
            }

            branches.firstOrNull { it != currentBranch }?.let { return it }
            branches.firstOrNull()?.let { return it }
        }

        return resolveSelectedCommitHash(e)
    }

    private fun extractBranchName(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value
            else -> runCatching {
                value.javaClass.getMethod("getName").invoke(value) as? String
            }.getOrNull() ?: value.toString()
        }
    }

    private fun resolveSelectedCommitHash(e: AnActionEvent): String? {
        val vcsLog = e.getData(VcsLogDataKeys.VCS_LOG) ?: return null
        val selectedCommits = runCatching {
            vcsLog.javaClass.getMethod("getSelectedCommits").invoke(vcsLog) as? Collection<*>
        }.getOrNull() ?: return null

        return selectedCommits.firstNotNullOfOrNull { extractCommitHash(it) }
    }

    private fun extractCommitHash(value: Any?): String? {
        if (value == null) return null
        if (value is String && value.matches(Regex("^[0-9a-fA-F]{7,40}$"))) return value

        val hashObject = runCatching {
            value.javaClass.getMethod("getHash").invoke(value)
        }.getOrNull() ?: return null

        return runCatching {
            hashObject.javaClass.getMethod("asString").invoke(hashObject) as? String
        }.getOrNull()
            ?: runCatching {
                hashObject.javaClass.getMethod("toShortString").invoke(hashObject) as? String
            }.getOrNull()
            ?: hashObject.toString()
    }
}
