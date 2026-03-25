package org.openprojectx.ai.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import org.openprojectx.ai.plugin.core.ContractType
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.core.GenerationRequest
import org.openprojectx.ai.plugin.core.PromptBuilder
import org.slf4j.LoggerFactory


class GenerateTestsService(private val project: Project) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val notificationState = OpenApiNotificationStateService.getInstance(project)

    fun generate(ui: GenerateTestsDialog.UiResult, file: VirtualFile, contractText: String) {
        notificationState.setState(file.path, GenerationUiState.Generating)
        EditorNotifications.getInstance(project).updateNotifications(file)

        val authSession = LlmAuthSessionService.getInstance(project)
        val config = LlmSettingsLoader.loadConfig(project)

        val contractType = when {
            OpenApiHeuristics.looksLikeOpenApi(file, contractText) -> ContractType.OPENAPI
            JavaHeuristics.looksLikeJavaSource(file, contractText) -> ContractType.JAVA
            else -> ContractType.OPENAPI
        }

        val effectiveFramework = if (contractType == ContractType.JAVA) {
            Framework.REST_ASSURED
        } else {
            ui.framework
        }

        val effectiveLocation = if (contractType == ContractType.JAVA) {
            JavaHeuristics.deriveTestLocationForMainJava(file, project.basePath)
                ?.takeIf { it.isNotBlank() }
                ?: ui.location
        } else {
            ui.location
        }

        val packageName = when (val frameworkConfig = ui.frameworkConfig) {
            is FrameworkUiConfig.RestAssured -> frameworkConfig.packageName
            FrameworkUiConfig.None -> null
        }?.takeIf { it.isNotBlank() } ?: if (contractType == ContractType.JAVA) {
            JavaHeuristics.derivePackageNameForJava(file, project.basePath)
        } else null

        val req = GenerationRequest(
            contractText = contractText,
            framework = effectiveFramework,
            contractType = contractType,
            baseUrl = ui.baseUrl,
            location = effectiveLocation,
            packageName = packageName,
            className = ui.className,
            outputNotes = ui.notes
        )

        val prompt = PromptBuilder.build(req, config.prompts.generation)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating tests by AI", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Preparing generation request..."
                    indicator.fraction = 0.15

                    indicator.text = "Calling LLM provider..."
                    indicator.fraction = 0.45
                    val code = authSession.withReloginOnUnauthorized { settings ->
                        val provider = LlmProviderFactory.create(settings)
                        kotlinx.coroutines.runBlocking { provider.generateCode(prompt) }
                    }

                    indicator.text = "Post-processing generated code..."
                    indicator.fraction = 0.7
                    val sanitizedCode = sanitizeGeneratedCode(code)

                    indicator.text = "Writing test file to project..."
                    indicator.fraction = 0.9
                    writeGenerated(
                        project = project,
                        framework = effectiveFramework,
                        location = effectiveLocation,
                        packageName = packageName,
                        cls = ui.className,
                        code = sanitizedCode
                    )

                    indicator.text = "Finalizing..."
                    indicator.fraction = 1.0

                    notificationState.setState(file.path, GenerationUiState.Done)
                    EditorNotifications.getInstance(project).updateNotifications(file)

                    ApplicationManager.getApplication().invokeLater {
                        Thread {
                            Thread.sleep(3000)
                            notificationState.clearState(file.path)
                            ApplicationManager.getApplication().invokeLater {
                                EditorNotifications.getInstance(project).updateNotifications(file)
                            }
                        }.start()
                    }
                } catch (e: Exception) {
                    log.error("Test generation failed:", e)
                    notificationState.clearState(file.path)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                    Notifications.error(project, "Test generation failed:", e.message ?: e.toString())
                }
            }
        })
    }

    private fun writeGenerated(
        project: Project,
        framework: Framework,
        location: String,
        packageName: String?,
        cls: String,
        code: String
    ) {
        val projectRoot = project.guessProjectDir()
            ?: throw IllegalStateException("Cannot resolve project root")

        val normalizedLocation = resolveOutputLocation(
            framework = framework,
            location = location,
            packageName = packageName
        )

        val fileName = when (framework) {
            Framework.REST_ASSURED -> "$cls.java"
            Framework.KARATE -> "$cls.feature"
        }

        var targetFile: VirtualFile? = null
        var previousContent: String? = null

        WriteCommandAction.runWriteCommandAction(project) {
            val targetDir = if (normalizedLocation.isBlank()) {
                projectRoot
            } else {
                VfsUtil.createDirectoryIfMissing(projectRoot, normalizedLocation)
                    ?: throw IllegalStateException("Cannot create target directory: $normalizedLocation")
            }

            val existing = targetDir.findChild(fileName)
            previousContent = existing?.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            val target = existing ?: targetDir.createChildData(this, fileName)
            target.setBinaryContent(code.toByteArray(Charsets.UTF_8))
            targetFile = target
        }

        val createdFile = targetFile ?: return
        val relativePath = createdFile.path.removePrefix(projectRoot.path).removePrefix("/")

        Notifications.notifyFileGenerated(
            project = project,
            title = "Test file generated",
            message = relativePath,
            file = createdFile
        )

        ContextBoxStateService.getInstance(project).recordGeneration(
            className = cls,
            targetPath = relativePath,
            diff = buildCodeDiff(relativePath, previousContent, code)
        )
    }


    private fun buildCodeDiff(path: String, before: String?, after: String): String {
        val beforeText = before ?: ""
        if (beforeText == after) {
            return "No content change for $path"
        }

        val beforeLines = beforeText.lines()
        val afterLines = after.lines()

        val builder = StringBuilder()
        builder.append("--- a/").append(path).append("\n")
        builder.append("+++ b/").append(path).append("\n")
        builder.append("@@ -1,").append(beforeLines.size).append(" +1,").append(afterLines.size).append(" @@\n")

        if (beforeLines.isNotEmpty()) {
            beforeLines.forEach { builder.append('-').append(it).append("\n") }
        }
        if (afterLines.isNotEmpty()) {
            afterLines.forEach { builder.append('+').append(it).append("\n") }
        }

        return builder.toString().trimEnd()
    }

    private fun resolveOutputLocation(
        framework: Framework,
        location: String,
        packageName: String?
    ): String {
        val normalizedLocation = normalizePath(location)

        return when (framework) {
            Framework.REST_ASSURED -> {
                val packagePath = packageName
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.replace('.', '/')
                    ?.replace('\\', '/')
                    ?.trim('/')

                listOfNotNull(
                    normalizedLocation.takeIf { it.isNotBlank() },
                    packagePath?.takeIf { it.isNotBlank() }
                ).joinToString("/")
            }

            Framework.KARATE -> normalizedLocation
        }
    }

    private fun normalizePath(path: String): String =
        path.trim()
            .replace('\\', '/')
            .removePrefix("/")
            .removeSuffix("/")

    private fun sanitizeGeneratedCode(raw: String): String {
        val trimmed = raw.trim()
        val withoutStartFence = trimmed.replaceFirst(Regex("^```(?:\\w+)?\\s*\\n?"), "")
        return withoutStartFence.replaceFirst(Regex("\\n?```\\s*$"), "").trim()
    }
}
