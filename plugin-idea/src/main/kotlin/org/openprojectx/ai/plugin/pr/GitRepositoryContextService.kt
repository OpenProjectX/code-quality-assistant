package org.openprojectx.ai.plugin.pr

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import java.io.File

data class GitRepositoryContext(
    val remoteUrl: String,
    val currentBranch: String,
    val repositoryRoot: String
)

object GitRepositoryContextService {

    fun resolve(project: Project): GitRepositoryContext {
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: error("No Git repository found for project")

        val remoteUrl = repository.remotes
            .firstOrNull { it.name == "origin" }
            ?.firstUrl
            ?: repository.remotes.firstOrNull()?.firstUrl
            ?: error("No Git remote found")

        val branch = repository.currentBranch?.name
            ?: error("Cannot determine current branch")

        return GitRepositoryContext(
            remoteUrl = remoteUrl,
            currentBranch = branch,
            repositoryRoot = repository.root.path
        )
    }

    fun getDiffAgainstTarget(project: Project, targetBranch: String): String {
        val context = resolve(project)

        val process = ProcessBuilder(
            "git",
            "diff",
            "origin/$targetBranch...HEAD"
        )
            .directory(File(context.repositoryRoot))
            .redirectErrorStream(true)
            .start()

        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        return text
    }
}