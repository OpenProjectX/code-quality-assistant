package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.VcsDataKeys
import org.openprojectx.ai.plugin.pr.AiPullRequestService
import org.openprojectx.ai.plugin.pr.GitRepositoryContextService
import org.openprojectx.ai.plugin.pr.PullRequestOptionsPanel
import org.openprojectx.ai.plugin.pr.PullRequestSettingsState
import org.openprojectx.ai.plugin.pr.PullRequestUiOptions
import java.io.File
import javax.swing.JComponent

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
        val dialog = PushAndCreatePrDialog(
            project = project,
            initialCreateAfterPush = settings.createAfterPush,
            initialTargetBranch = settings.targetBranch
        )
        if (!dialog.showAndGet()) return

        val options = dialog.getOptions()
        if (options.createAfterPush && options.targetBranch.isBlank()) {
            Notifications.error(project, "Push and Create PR", "Target branch is required when creating a pull request.")
            return
        }

        settings.createAfterPush = options.createAfterPush
        settings.targetBranch = options.targetBranch.ifBlank { settings.targetBranch }
        PullRequestSettingsState.getInstance(project).loadState(settings)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Push and Create PR", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Pushing ${repoContext.currentBranch} to origin..."
                    runGitPush(repoContext.repositoryRoot, repoContext.currentBranch)

                    if (!options.createAfterPush) {
                        Notifications.info(project, "Push and Create PR", "Push succeeded.")
                        return
                    }

                    indicator.text = "Collecting branch diff..."
                    val diff = GitRepositoryContextService.getDiffAgainstTarget(project, options.targetBranch)
                    if (diff.isBlank()) {
                        Notifications.warn(project, "Push and Create PR", "Push succeeded, but no branch diff found.")
                        return
                    }

                    indicator.text = "Generating branch summary..."
                    val summary = AiBranchDiffSummaryService(project).generate(
                        sourceBranch = repoContext.currentBranch,
                        targetBranch = options.targetBranch,
                        diff = diff
                    )

                    indicator.text = "Creating pull request..."
                    val pr = AiPullRequestService(project).createAfterPush(
                        remoteUrl = repoContext.remoteUrl,
                        sourceBranch = repoContext.currentBranch,
                        targetBranch = options.targetBranch,
                        diff = diff,
                        summaryComment = summary
                    )

                    ContextBoxStateService.getInstance(project).recordBranchSummary(
                        targetBranch = options.targetBranch,
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
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            error("git push failed: $output")
        }
    }
}

private class PushAndCreatePrDialog(
    project: Project,
    initialCreateAfterPush: Boolean,
    initialTargetBranch: String
) : DialogWrapper(project) {

    private val optionsPanel = PullRequestOptionsPanel(
        initialCreateAfterPush = initialCreateAfterPush,
        initialTargetBranch = initialTargetBranch
    )

    init {
        title = "Push"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return optionsPanel.panel
    }

    fun getOptions(): PullRequestUiOptions {
        return optionsPanel.getOptions()
    }
}
