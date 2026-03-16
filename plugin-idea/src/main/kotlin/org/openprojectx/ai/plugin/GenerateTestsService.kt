package org.openprojectx.ai.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
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

        val packageName = when (val frameworkConfig = ui.frameworkConfig) {
            is FrameworkUiConfig.RestAssured -> frameworkConfig.packageName
            FrameworkUiConfig.None -> null
        }

        val req = GenerationRequest(
            contractText = contractText,
            framework = ui.framework,
            baseUrl = ui.baseUrl,
            location = ui.location,
            packageName = packageName,
            className = ui.className,
            outputNotes = ui.notes
        )

        val prompt = PromptBuilder.build(req)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val code = authSession.withReloginOnUnauthorized { settings ->
                    val provider = LlmProviderFactory.create(settings)
                    kotlinx.coroutines.runBlocking { provider.generateCode(prompt) }
                }
                writeGenerated(
                    project = project,
                    contractFile = file,
                    framework = ui.framework,
                    location = ui.location,
                    packageName = packageName,
                    cls = ui.className,
                    code = code
                )

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
    }

    private fun writeGenerated(
        project: Project,
        contractFile: VirtualFile,
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

        WriteCommandAction.runWriteCommandAction(project) {
            val targetDir = if (normalizedLocation.isBlank()) {
                projectRoot
            } else {
                VfsUtil.createDirectoryIfMissing(projectRoot, normalizedLocation)
                    ?: throw IllegalStateException("Cannot create target directory: $normalizedLocation")
            }

            val existing = targetDir.findChild(fileName)
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
}
