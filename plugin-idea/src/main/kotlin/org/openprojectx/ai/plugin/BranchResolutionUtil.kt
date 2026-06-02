package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.VcsLogCommitSelection
import com.intellij.vcs.log.VcsLogDataKeys
import com.intellij.vcs.log.VcsRef
import git4idea.repo.GitRepositoryManager
import java.io.File

object BranchResolutionUtil {

    fun resolveCurrentBranch(project: Project): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        return repo?.currentBranchName ?: "HEAD"
    }

    fun resolveTargetRef(
        project: Project,
        e: AnActionEvent,
        currentBranch: String
    ): String? {
        val normalizedCurrent = normalizeRefName(currentBranch)

        // 1. VCS_LOG_BRANCHES gives the branch refs at the right-clicked position
        //    in the VCS log graph. This is the most direct way to know which branch
        //    the user intended to compare with.
        val vcsRefs = e.getData(VcsLogDataKeys.VCS_LOG_BRANCHES)
        if (vcsRefs != null) {
            for (ref in vcsRefs) {
                val name = normalizeRefName(extractRefName(ref))
                if (name.isNotEmpty() && name != normalizedCurrent) return name
            }
        }

        // 2. Fall back to the selected commit: resolve its hash, then find which
        //    branch points to it via git for-each-ref.
        val selectedHash = resolveSelectedCommitHash(e)
        if (selectedHash != null) {
            resolveBranchAtCommit(project, selectedHash, currentBranch)?.let { return it }
            return selectedHash
        }

        // 3. Try the VCS Log branch filter (user may have filtered by branch name)
        resolveBranchFromLogUi(e, currentBranch)?.let { return it }

        // 4. Last resort: other local/remote branches in the repository
        return resolveRepositoryBranchFallback(project, currentBranch)
    }

    private fun extractRefName(value: Any?): String {
        return when (value) {
            is VcsRef -> value.name
            is String -> value
            null -> ""
            else -> value.toString()
        }
    }

    fun normalizeRefName(name: String): String =
        name.removePrefix("refs/heads/").removePrefix("refs/remotes/")

    fun resolveBranchAtCommit(
        project: Project,
        commitHash: String,
        currentBranch: String
    ): String? {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        val process = ProcessBuilder(
            "git", "for-each-ref",
            "--points-at=$commitHash",
            "--format=%(refname)",
            "refs/heads/", "refs/remotes/"
        )
            .directory(File(repo.root.path))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) return null

        val normalizedCurrent = normalizeRefName(currentBranch)
        val localBranches = mutableListOf<String>()
        val remoteBranches = mutableListOf<String>()

        for (line in output.lines()) {
            val ref = line.trim()
            when {
                ref.startsWith("refs/heads/") -> {
                    val name = ref.removePrefix("refs/heads/")
                    if (name.isNotBlank() && name != normalizedCurrent) localBranches.add(name)
                }
                ref.startsWith("refs/remotes/") -> {
                    val fullName = ref.removePrefix("refs/remotes/")
                    val shortName = fullName.substringAfter("/")
                    if (fullName.isNotBlank() && fullName != normalizedCurrent && shortName != normalizedCurrent) {
                        remoteBranches.add(fullName)
                    }
                }
            }
        }

        val preferred = listOf("main", "master", "develop", "dev")
        preferred.firstOrNull { it in localBranches }?.let { return it }
        localBranches.firstOrNull()?.let { return it }
        preferred.firstOrNull { pref -> remoteBranches.any { it.substringAfter("/") == pref } }?.let { pref ->
            remoteBranches.first { it.substringAfter("/") == pref }.also { return it }
        }
        return remoteBranches.firstOrNull()
    }

    fun resolveBranchFromLogUi(e: AnActionEvent, currentBranch: String): String? {
        val vcsLogUi = e.getData(VcsLogDataKeys.VCS_LOG_UI) ?: return null
        val filterUi = runCatching { vcsLogUi.javaClass.getMethod("getFilterUi").invoke(vcsLogUi) }.getOrNull() ?: return null
        val filters = runCatching { filterUi.javaClass.getMethod("getFilters").invoke(filterUi) }.getOrNull() ?: return null
        val branchFilter = runCatching { filters.javaClass.getMethod("getBranchFilter").invoke(filters) }.getOrNull() ?: return null

        val values = runCatching {
            branchFilter.javaClass.getMethod("getValues").invoke(branchFilter) as? Collection<*>
        }.getOrNull().orEmpty().mapNotNull { it?.toString()?.trim() }

        if (values.isNotEmpty()) {
            val normalizedCurrent = normalizeRefName(currentBranch)
            values.firstOrNull { it.isNotBlank() && it != currentBranch && normalizeRefName(it) != normalizedCurrent }?.let { return it }
            values.firstOrNull()?.let { return it }
        }

        val textPresentation = runCatching {
            branchFilter.javaClass.getMethod("getTextPresentation").invoke(branchFilter)
        }.getOrNull()?.toString().orEmpty()

        if (textPresentation.isBlank()) return null
        val normalizedCurrent = normalizeRefName(currentBranch)
        return textPresentation
            .split(",", " ", "|")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != currentBranch && normalizeRefName(it) != normalizedCurrent }
    }

    fun resolveRepositoryBranchFallback(
        project: Project,
        currentBranch: String
    ): String? {
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        val normalizedCurrent = normalizeRefName(currentBranch)
        val localBranches = repository.branches.localBranches.map { normalizeRefName(it.name) }
            .filter { it != normalizedCurrent }

        val preferred = listOf("main", "master", "develop", "dev")
        preferred.firstOrNull { it in localBranches }?.let { return it }
        localBranches.firstOrNull()?.let { return it }

        val remoteBranches = repository.branches.remoteBranches.map {
            normalizeRefName(it.name)
        }.filter { it != normalizedCurrent && it !in localBranches }
        preferred.firstOrNull { pref -> remoteBranches.any { it.substringAfter("/") == pref } }?.let { pref ->
            remoteBranches.first { it.substringAfter("/") == pref }.also { return it }
        }
        return remoteBranches.firstOrNull()
    }

    fun resolveSelectedCommitHash(e: AnActionEvent): String? {
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION) ?: return null
        val commits = selection.commits
        if (commits.isEmpty()) return null
        return commits.first().hash.asString()
    }
}
