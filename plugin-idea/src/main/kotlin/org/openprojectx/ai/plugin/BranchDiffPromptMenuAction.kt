package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
//import com.intellij.openapi.actionSystem.DumbAware
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

class BranchDiffPromptMenuAction : ActionGroup("Analyze Branch Diff", true), DumbAware {

    init {
        templatePresentation.icon = OpenProjectXIcons.GenerateTests
    }

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
        val sourceBranch = BranchResolutionUtil.resolveCurrentBranch(project)
        val targetRef = BranchResolutionUtil.resolveTargetRef(project, e, sourceBranch)
        if (targetRef == null) {
            Notifications.warn(
                project,
                "Summarize Branch Diff",
                "Please select a branch or commit in VCS Log to compare with current branch $sourceBranch."
            )
            return
        }

        saveDefaultBranchDiffPromptProfile(project, promptName)
        ButtonUsageReportService.getInstance(project).recordPromptUsage("branch.diff", promptName)

        // Prevent concurrent branch diff analysis
        val ctxBox = ContextBoxStateService.getInstance(project)
        if (!ctxBox.tryStartBranchDiff()) {
            Notifications.warn(project, "Summarize Branch Diff", "A branch diff analysis is already in progress.")
            return
        }

        // Show tool window immediately and add user bubble with branch info
        ToolWindowManager.getInstance(project).getToolWindow("AI Context Box")?.show(null)
        ctxBox.addUserMessage("Analyze changes on $sourceBranch → $targetRef")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Summarizing Branch Diff", true) {
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
                    }
                } catch (ex: Exception) {
                    Notifications.error(project, "Summarize Branch Diff failed", ex.message ?: ex.toString())
                } finally {
                    ContextBoxStateService.getInstance(project).finishBranchDiff()
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

}
