package org.openprojectx.ai.plugin.pr

import com.intellij.openapi.project.Project
import org.openprojectx.ai.plugin.Notifications

class PostPushPullRequestService(private val project: Project) {

    fun createIfEnabled(options: PullRequestUiOptions, token: String) {
        if (!options.createAfterPush) return

        val repo = GitRepositoryContextService.resolve(project)
        val diff = GitRepositoryContextService.getDiffAgainstTarget(project, options.targetBranch)

        if (diff.isBlank()) {
            Notifications.error(
                project,
                "Create Pull Request",
                "No changes found between ${repo.currentBranch} and ${options.targetBranch}."
            )
            return
        }

        val result = AiPullRequestService(project).createAfterPush(
            remoteUrl = repo.remoteUrl,
            sourceBranch = repo.currentBranch,
            targetBranch = options.targetBranch,
            diff = diff,
            providerToken = token
        )

        Notifications.info(
            project,
            "Pull Request Created",
            result.url
        )
    }
}