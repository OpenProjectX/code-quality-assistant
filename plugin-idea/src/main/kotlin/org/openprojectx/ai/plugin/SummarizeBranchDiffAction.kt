package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.diff.util.DiffDataKeys
import com.intellij.vcs.log.VcsLogDataKeys

class SummarizeBranchDiffAction : AnAction("Summarize Branch Diff") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val branches = resolveComparedBranches(e) ?: resolveComparedBranchesFromDiffTitle(e)

        if (branches == null) {
            Notifications.warn(
                project,
                "Summarize Branch Diff",
                "This action is only available while comparing two branches."
            )
            return
        }

        val (sourceBranch, targetBranch) = branches

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Summarizing Branch Diff", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Collecting branch diff for $sourceBranch vs $targetBranch..."
                    val diff = GitDiffProvider.getDiffBetweenBranches(project, sourceBranch, targetBranch)

                    if (diff.isBlank()) {
                        Notifications.info(
                            project,
                            "Summarize Branch Diff",
                            "No changes found between $sourceBranch and $targetBranch."
                        )
                        return
                    }

                    indicator.text = "Generating summary..."
                    val summary = AiBranchDiffSummaryService(project).generate(sourceBranch, targetBranch, diff)

                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, summary.trim(), "Branch Diff Summary")
                    }
                } catch (ex: Exception) {
                    Notifications.error(
                        project,
                        "Summarize Branch Diff failed",
                        ex.message ?: ex.toString()
                    )
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            (resolveComparedBranches(e) != null || resolveComparedBranchesFromDiffTitle(e) != null)
    }

    private fun resolveComparedBranches(e: AnActionEvent): Pair<String, String>? {
        val rawBranches = e.getData(VcsLogDataKeys.VCS_LOG_BRANCHES) as? Collection<*>
            ?: return null

        val branches = LinkedHashSet<String>()
        for (rawBranch in rawBranches) {
            val name = extractBranchName(rawBranch)?.trim().orEmpty()
            if (name.isNotEmpty()) {
                branches.add(name)
            }
        }

        if (branches.size != 2) return null

        val values = branches.toList()
        return Pair(values[0], values[1])
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

    private fun resolveComparedBranchesFromDiffTitle(e: AnActionEvent): Pair<String, String>? {
        val title = e.getData(DiffDataKeys.DIFF_REQUEST)?.title?.trim().orEmpty()
        if (title.isEmpty()) return null

        val separators = listOf("...", "..", " vs ", " and ", " ↔ ")
        for (separator in separators) {
            val parts = title.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size == 2) {
                return Pair(parts[0], parts[1])
            }
        }
        return null
    }
}
