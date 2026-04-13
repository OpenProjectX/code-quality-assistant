package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnActionEvent
//import com.intellij.openapi.actionSystem.DumbAwareAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.vcs.log.VcsLogDataKeys
import git4idea.repo.GitRepositoryManager

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
        val sourceBranch = resolveCurrentBranch(project)
        val targetRef = resolveTargetRef(project, e, sourceBranch)

        if (targetRef == null) {
            Notifications.warn(
                project,
                "Summarize Branch Diff",
                "[$sourceTag] Please select a branch or commit in VCS Log to compare with current branch $sourceBranch."
            )
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Summarizing Branch Diff", false) {
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
                        Notifications.info(
                            project,
                            "Branch Diff Summary",
                            "Summary updated in AI Context Box > Branch Analysis."
                        )
                    }
                } catch (ex: Exception) {
                    Notifications.error(
                        project,
                        "Summarize Branch Diff failed [$sourceTag]",
                        ex.message ?: ex.toString()
                    )
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun resolveTargetRef(
        project: com.intellij.openapi.project.Project,
        e: AnActionEvent,
        currentBranch: String
    ): String? {
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

        resolveBranchFromLogUi(e, currentBranch)?.let { return it }
        resolveSelectedCommitHash(e)?.let { return it }

        return resolveRepositoryBranchFallback(project, currentBranch)
    }

    private fun resolveBranchFromLogUi(e: AnActionEvent, currentBranch: String): String? {
        val vcsLogUi = e.getData(VcsLogDataKeys.VCS_LOG_UI) ?: return null
        val filterUi = runCatching { vcsLogUi.javaClass.getMethod("getFilterUi").invoke(vcsLogUi) }.getOrNull() ?: return null
        val filters = runCatching { filterUi.javaClass.getMethod("getFilters").invoke(filterUi) }.getOrNull() ?: return null
        val branchFilter = runCatching { filters.javaClass.getMethod("getBranchFilter").invoke(filters) }.getOrNull() ?: return null

        val values = runCatching {
            branchFilter.javaClass.getMethod("getValues").invoke(branchFilter) as? Collection<*>
        }.getOrNull().orEmpty().mapNotNull { it?.toString()?.trim() }

        if (values.isNotEmpty()) {
            val normalizedCurrent = currentBranch.removePrefix("refs/heads/")
            values.firstOrNull { it.isNotBlank() && it != currentBranch && it != normalizedCurrent }?.let { return it }
            values.firstOrNull()?.let { return it }
        }

        val textPresentation = runCatching {
            branchFilter.javaClass.getMethod("getTextPresentation").invoke(branchFilter)
        }.getOrNull()?.toString().orEmpty()

        if (textPresentation.isBlank()) return null
        val normalizedCurrent = currentBranch.removePrefix("refs/heads/")
        return textPresentation
            .split(",", " ", "|")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != currentBranch && it != normalizedCurrent }
    }

    private fun resolveRepositoryBranchFallback(
        project: com.intellij.openapi.project.Project,
        currentBranch: String
    ): String? {
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        val localBranches = repository.branches.localBranches.map { it.name }
        val normalizedCurrent = currentBranch.removePrefix("refs/heads/")
        val candidates = localBranches.filter { it != normalizedCurrent && it != currentBranch }

        val preferred = listOf("main", "master", "develop", "dev")
        preferred.firstOrNull { it in candidates }?.let { return it }

        return candidates.firstOrNull()
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

    private fun resolveCurrentBranch(project: com.intellij.openapi.project.Project): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        return repo?.currentBranchName ?: "HEAD"
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
