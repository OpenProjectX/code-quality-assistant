package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnActionEvent
//import com.intellij.openapi.actionSystem.DumbAwareAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

open class SummarizeBranchDiffAction(
    tooltip: String = "Summarize Branch Differences (Default)",
    iconPath: String = "/icons/blue-bulb.svg",
    private val sourceTag: String = "default"
) : DumbAwareAction(
    null,
    tooltip,
    IconLoader.getIcon(iconPath, SummarizeBranchDiffAction::class.java)
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sourceBranch = BranchResolutionUtil.resolveCurrentBranch(project)
        val targetRef = BranchResolutionUtil.resolveTargetRef(project, e, sourceBranch)

        if (targetRef == null) {
            Notifications.warn(
                project,
                "Summarize Branch Diff",
                "[$sourceTag] Please select a branch or commit in VCS Log to compare with current branch $sourceBranch."
            )
            return
        }
        val selectedPrompt = LlmSettingsLoader.loadConfig(project).prompts.profiles.branchDiffSummary.selected
        ButtonUsageReportService.getInstance(project).recordPromptUsage("branch.diff", selectedPrompt)

        // Prevent concurrent branch diff analysis
        val ctxBox = ContextBoxStateService.getInstance(project)
        if (!ctxBox.tryStartBranchDiff()) {
            Notifications.warn(project, "Summarize Branch Diff", "[$sourceTag] A branch diff analysis is already in progress.")
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
                        project,
                        sourceBranch = sourceBranch,
                        targetBranch = targetRef
                    )

                    if (diff.isBlank()) {
                        Notifications.info(
                            project,
                            "Summarize Branch Diff",
                            "[$sourceTag] No changes found between $sourceBranch and $targetRef."
                        )
                        return
                    }

                    indicator.text = "Generating summary..."
                    val summary = AiBranchDiffSummaryService(project).generate(
                        sourceBranch = sourceBranch,
                        targetBranch = targetRef,
                        diff = diff
                    )

                    ApplicationManager.getApplication().invokeLater {
                        ContextBoxStateService.getInstance(project).recordBranchSummary(
                            targetBranch = targetRef,
                            sourceBranch = sourceBranch,
                            summary = summary
                        )
                    }
                } catch (ex: Exception) {
                    Notifications.error(
                        project,
                        "Summarize Branch Diff failed [$sourceTag]",
                        ex.message ?: ex.toString()
                    )
                } finally {
                    ContextBoxStateService.getInstance(project).finishBranchDiff()
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }


}

class SummarizeProbeVcsLogInternalToolbarAction : SummarizeBranchDiffAction(
    tooltip = "Summarize Branch Differences [Probe Vcs.Log.Toolbar.Internal]",
    iconPath = "/icons/blue-bulb.svg",
    sourceTag = "Vcs.Log.Toolbar.Internal"
)

class SummarizeProbeVcsLogContextMenuAction : SummarizeBranchDiffAction(
    tooltip = "Analyze Current Branch vs Selected Branch/Commit",
    iconPath = "/icons/blue-bulb.svg",
    sourceTag = "Vcs.Log.ContextMenu"
)
