package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import git4idea.repo.GitRepositoryManager
import java.io.File

object GitDiffProvider {

    fun getDiffForSelectedChanges(project: Project, changes: List<Change>, unversionedFiles: List<FilePath>): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: error("No Git repository found for project")
        val repoRoot = repo.root.path

        fun toRelativePath(absolutePath: String): String? {
            val f = File(absolutePath)
            val canonical = if (f.isAbsolute) f.canonicalPath else File(repoRoot, absolutePath).canonicalPath
            return if (canonical != null && canonical.startsWith(repoRoot)) {
                canonical.removePrefix(repoRoot).removePrefix("/").removePrefix("\\")
            } else null
        }

        val filePaths = changes.mapNotNull { change ->
            val revision = change.afterRevision ?: change.beforeRevision
            revision?.file?.path?.let { toRelativePath(it) }
        } + unversionedFiles.mapNotNull { it.path?.let { p -> toRelativePath(p) } }
        val uniquePaths = filePaths.distinct()

        if (uniquePaths.isEmpty()) return ""

        val stagedProcess = ProcessBuilder(
            mutableListOf("git", "diff", "--cached", "--") + uniquePaths
        )
            .directory(File(repo.root.path))
            .redirectErrorStream(true)
            .start()

        val staged = stagedProcess.inputStream.bufferedReader().use { it.readText() }
        stagedProcess.waitFor()

        val unstagedProcess = ProcessBuilder(
            mutableListOf("git", "diff", "--") + uniquePaths
        )
            .directory(File(repo.root.path))
            .redirectErrorStream(true)
            .start()

        val unstaged = unstagedProcess.inputStream.bufferedReader().use { it.readText() }
        unstagedProcess.waitFor()

        return buildString {
            if (staged.isNotBlank()) {
                appendLine(staged.trimEnd())
            }
            if (unstaged.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(unstaged.trimEnd())
            }
        }
    }


    fun getAllUncommittedDiff(project: Project): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: error("No Git repository found for project")
        val repoDir = File(repo.root.path)

        val stagedProcess = ProcessBuilder("git", "diff", "--cached")
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val staged = stagedProcess.inputStream.bufferedReader().use { it.readText() }
        stagedProcess.waitFor()

        val unstagedProcess = ProcessBuilder("git", "diff")
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val unstaged = unstagedProcess.inputStream.bufferedReader().use { it.readText() }
        unstagedProcess.waitFor()

        return buildString {
            if (staged.isNotBlank()) {
                appendLine(staged.trimEnd())
            }
            if (unstaged.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(unstaged.trimEnd())
            }
        }
    }

    fun getDiffBetweenBranches(project: Project, sourceBranch: String, targetBranch: String): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: error("No Git repository found for project")

        val process = ProcessBuilder(
            "git",
            "diff",
            "$targetBranch...$sourceBranch"
        )
            .directory(File(repo.root.path))
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            error("Failed to compare branches: $output")
        }

        return output
    }

    fun getDiffForFiles(project: Project, filePaths: List<String>): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: error("No Git repository found for project")
        if (filePaths.isEmpty()) return ""

        val process = ProcessBuilder(
            listOf("git", "diff", "--") + filePaths
        )
            .directory(File(repo.root.path))
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            error("Failed to collect file diff: $output")
        }

        return output
    }

}
