package org.openprojectx.ai.plugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object SonarCubeLocalScanner {

    fun scan(project: Project, indicator: ProgressIndicator): SonarCubeResult {
        if (DumbService.isDumb(project)) {
            throw IllegalStateException(
                "IntelliJ is still indexing. Please wait for indexing to complete and try again."
            )
        }

        val allIssues = mutableListOf<SonarCubeIssue>()
        val sourceFiles = collectSourceFiles(project)

        if (sourceFiles.isEmpty()) {
            return emptyResult(project, "No source files found")
        }

        indicator.isIndeterminate = false
        indicator.text = "Running local code analysis..."

        for ((index, file) in sourceFiles.withIndex()) {
            ProgressManager.checkCanceled()
            indicator.fraction = index.toDouble() / sourceFiles.size
            indicator.text2 = file.presentableUrl

            try {
                val fileIssues = ReadAction.nonBlocking<List<SonarCubeIssue>> {
                    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@nonBlocking emptyList()
                    analyzeFile(project, file, psiFile)
                }.executeSynchronously()
                synchronized(allIssues) { allIssues.addAll(fileIssues) }
            } catch (_: Exception) {
                // Skip files that can't be analyzed
            }
        }

        val bugs = allIssues.count { it.type.equals("BUG", ignoreCase = true) }
        val vulnerabilities = allIssues.count { it.type.equals("VULNERABILITY", ignoreCase = true) }
        val codeSmells = allIssues.count { it.type.equals("CODE_SMELL", ignoreCase = true) }

        val localCoverage = collectLocalCoverage(project, sourceFiles)

        return SonarCubeResult(
            projectKey = project.name,
            serverUrl = "local",
            coverage = null, lineCoverage = null, branchCoverage = null, uncoveredLines = null,
            bugs = bugs, vulnerabilities = vulnerabilities, codeSmells = codeSmells,
            issues = allIssues,
            reportTimestamp = "Local scan ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}",
            fileCoverages = localCoverage
        )
    }

    private fun collectLocalCoverage(
        project: Project,
        sourceFiles: List<VirtualFile>
    ): List<SonarQubeFileCoverage> {
        try {
            // Use reflection to avoid compile-time dependency on coverage module
            val managerClass = Class.forName("com.intellij.coverage.CoverageDataManager")
            val getInstance = managerClass.getMethod("getInstance", Project::class.java)
            val coverageManager = getInstance.invoke(null, project)
            val getBundle = coverageManager.javaClass.getMethod("getCurrentSuitesBundle")
            val bundle = getBundle.invoke(coverageManager) ?: return emptyList()

            val result = mutableListOf<SonarQubeFileCoverage>()
            val getCoverageMethod = bundle.javaClass.getMethod("getCoverageData", VirtualFile::class.java)

            for (file in sourceFiles) {
                val relPath = relativePath(project, file)
                try {
                    val coverageData = getCoverageMethod.invoke(bundle, file) ?: continue
                    val getLineCount = coverageData.javaClass.getMethod("getLineCount")
                    val getCoveredLineCount = coverageData.javaClass.getMethod("getCoveredLineCount")
                    val total = getLineCount.invoke(coverageData) as? Int ?: 0
                    val covered = getCoveredLineCount.invoke(coverageData) as? Int ?: 0

                    if (total > 0) {
                        result.add(SonarQubeFileCoverage(
                            key = "LOCAL:$relPath",
                            path = relPath,
                            name = file.name,
                            coverage = covered * 100.0 / total,
                            uncoveredLines = (total - covered).coerceAtLeast(0)
                        ))
                    }
                } catch (_: Exception) {
                    // file not in coverage data
                }
            }

            return result.sortedBy { it.coverage ?: 100.0 }
        } catch (_: ClassNotFoundException) {
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun emptyResult(project: Project, message: String) = SonarCubeResult(
        projectKey = project.name,
        serverUrl = "local",
        coverage = null, lineCoverage = null, branchCoverage = null, uncoveredLines = null,
        bugs = 0, vulnerabilities = 0, codeSmells = 0,
        issues = emptyList(),
        reportTimestamp = message
    )

    private data class TextIssue(
        val line: Int,
        val offset: Int,
        val severity: String,
        val type: String,
        val rule: String,
        val message: String
    )

    fun scanFile(project: Project, virtualFile: VirtualFile): List<SonarCubeIssue> {
        return ReadAction.nonBlocking<List<SonarCubeIssue>> {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@nonBlocking emptyList()
            analyzeFile(project, virtualFile, psiFile)
        }.executeSynchronously()
    }

    private fun analyzeFile(project: Project, file: VirtualFile, psiFile: PsiFile): List<SonarCubeIssue> {
        val textIssues = mutableListOf<TextIssue>()
        val relativePath = relativePath(project, file)
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)

        val text = psiFile.text
        val lines = text.lines()

        for ((lineIndex, line) in lines.withIndex()) {
            val lineNumber = lineIndex + 1
            val stripped = line.trim()

            // TODO/FIXME/HACK comments
            val commentMatch = Regex("(//|#|/\\*)\\s*(TODO|FIXME|HACK|XXX)[: ]*(.*)").find(stripped)
            if (commentMatch != null) {
                val tag = commentMatch.groupValues[2]
                val detail = commentMatch.groupValues[3].ifBlank { "" }
                val offset = text.indexOf(line)
                textIssues.add(TextIssue(lineNumber, offset,
                    severity = "MINOR", type = "CODE_SMELL",
                    rule = "local:$tag-comment",
                    message = "$tag comment: ${detail.take(100)}"
                ))
            }

            // printStackTrace in any language
            if (stripped.contains("printStackTrace()") || stripped.contains(".printStackTrace()")) {
                val offset = text.indexOf(line)
                textIssues.add(TextIssue(lineNumber, offset,
                    severity = "CRITICAL", type = "BUG",
                    rule = "local:print-stacktrace",
                    message = "printStackTrace() found — replace with proper logging"
                ))
            }

            // System.out.println (Java) / println (Kotlin)
            if (Regex("System\\.out\\.println").containsMatchIn(stripped)) {
                val offset = text.indexOf(line)
                textIssues.add(TextIssue(lineNumber, offset,
                    severity = "MAJOR", type = "CODE_SMELL",
                    rule = "local:system-out",
                    message = "System.out.println() found — use a logger instead"
                ))
            }

            // Empty catch block
            if (Regex("catch\\s*\\([^)]*\\)\\s*\\{\\s*}").containsMatchIn(stripped)) {
                val offset = text.indexOf(line)
                textIssues.add(TextIssue(lineNumber, offset,
                    severity = "BLOCKER", type = "BUG",
                    rule = "local:empty-catch",
                    message = "Empty catch block silently swallows exceptions"
                ))
            }

            // Hardcoded secrets / tokens
            if (Regex("(?i)(password|secret|api[_-]?key|token)\\s*[:=]\\s*\"[^\"]+\"").containsMatchIn(stripped) ||
                Regex("(?i)(password|secret|api[_-]?key|token)\\s*[:=]\\s*'[^']+'").containsMatchIn(stripped)) {
                val offset = text.indexOf(line)
                textIssues.add(TextIssue(lineNumber, offset,
                    severity = "BLOCKER", type = "VULNERABILITY",
                    rule = "local:hardcoded-secret",
                    message = "Hardcoded secret/credential found — use environment variables or a vault"
                ))
            }
        }

        return textIssues.map { ti ->
            SonarCubeIssue(
                key = buildIssueKey(relativePath, ti.line, ti.offset),
                path = relativePath,
                line = ti.line,
                severity = ti.severity,
                type = ti.type,
                rule = ti.rule,
                message = ti.message
            )
        }
    }

    private fun relativePath(project: Project, file: VirtualFile): String =
        ProjectRootManager.getInstance(project).contentRoots
            .firstOrNull { root -> file.path.startsWith(root.path) }
            ?.let { root -> file.path.removePrefix(root.path).removePrefix("/").removePrefix("\\") }
            ?: file.name

    private fun collectSourceFiles(project: Project): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots
        val files = mutableListOf<VirtualFile>()
        val excludeDirs = setOf("build", "out", "target", ".git", ".idea", "node_modules", "__pycache__", ".gradle")

        for (root in roots) {
            collectFiles(root, files, excludeDirs)
        }

        return files.sortedBy { it.path }
    }

    private fun collectFiles(dir: VirtualFile, result: MutableList<VirtualFile>, excludeDirs: Set<String>) {
        if (!dir.isDirectory) return
        if (dir.name in excludeDirs) return
        if (dir.name.startsWith(".") && dir.name != ".") return

        for (child in dir.children) {
            if (child.isDirectory) {
                collectFiles(child, result, excludeDirs)
            } else if (isSourceFile(child)) {
                result.add(child)
            }
        }
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val name = file.name
        return name.endsWith(".java") || name.endsWith(".kt") ||
            name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".ts") ||
            name.endsWith(".go") || name.endsWith(".rs") || name.endsWith(".rb") ||
            name.endsWith(".xml") || name.endsWith(".yaml") || name.endsWith(".yml") ||
            name.endsWith(".properties") || name.endsWith(".json") || name.endsWith(".cs")
    }

    private fun buildIssueKey(path: String, line: Int, offset: Int): String =
        "LOCAL:$path:$line:$offset"
}