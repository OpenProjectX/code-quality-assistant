package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import org.openprojectx.ai.plugin.pr.AiPullRequestService
import org.openprojectx.ai.plugin.pr.GitRepositoryContextService
import org.openprojectx.ai.plugin.pr.PullRequestSettingsState
import java.io.File

class PushAndCreatePrAction : AnAction("Push and Create PR"), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repoContext = runCatching { GitRepositoryContextService.resolve(project) }.getOrElse {
            Notifications.error(project, "Push and Create PR", it.message ?: it.toString())
            return
        }

        val settings = PullRequestSettingsState.getInstance(project).state
        val targetBranch = Messages.showInputDialog(
            project,
            "Target branch for Pull Request:",
            "Push and Create PR",
            null,
            settings.targetBranch,
            null
        )?.trim().orEmpty()

        if (targetBranch.isBlank()) return
        settings.targetBranch = targetBranch
        PullRequestSettingsState.getInstance(project).loadState(settings)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Push and Create PR", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Pushing ${repoContext.currentBranch} to origin..."
                    runGitPush(repoContext.repositoryRoot, repoContext.currentBranch)

                    indicator.text = "Collecting branch diff..."
                    val diff = GitRepositoryContextService.getDiffAgainstTarget(project, targetBranch)
                    if (diff.isBlank()) {
                        Notifications.warn(project, "Push and Create PR", "Push succeeded, but no branch diff found.")
                        return
                    }

                    indicator.text = "Generating branch summary..."
                    val summary = AiBranchDiffSummaryService(project).generate(
                        sourceBranch = repoContext.currentBranch,
                        targetBranch = targetBranch,
                        diff = diff
                    )

                    indicator.text = "Creating pull request..."
                    val pr = AiPullRequestService(project).createAfterPush(
                        remoteUrl = repoContext.remoteUrl,
                        sourceBranch = repoContext.currentBranch,
                        targetBranch = targetBranch,
                        diff = diff,
                        summaryComment = summary
                    )

                    ContextBoxStateService.getInstance(project).recordBranchSummary(
                        targetBranch = targetBranch,
                        sourceBranch = repoContext.currentBranch,
                        summary = summary
                    )
                    Notifications.info(project, "Push and Create PR", "PR created: ${pr.url}")
                } catch (ex: Exception) {
                    Notifications.error(project, "Push and Create PR failed", ex.message ?: ex.toString())
                }
            }
        })
    }

    private fun runGitPush(repositoryRoot: String, branch: String) {
        val process = ProcessBuilder("git", "push", "origin", branch)
            .directory(File(repositoryRoot))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            error("git push failed: $output")
        }
    }
}
