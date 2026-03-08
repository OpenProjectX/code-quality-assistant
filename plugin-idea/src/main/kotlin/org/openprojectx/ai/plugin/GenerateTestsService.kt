package org.openprojectx.ai.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.core.GenerationRequest
import org.openprojectx.ai.plugin.core.PromptBuilder
import org.slf4j.LoggerFactory


class GenerateTestsService(private val project: Project) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun generate(ui: GenerateTestsDialog.UiResult, file: VirtualFile, contractText: String) {
        val settings = LlmSettingsLoader.load(project)
        val provider = LlmProviderFactory.create(settings)

        val packageName = when (val frameworkConfig = ui.frameworkConfig) {
            is FrameworkUiConfig.RestAssured -> frameworkConfig.packageName
            FrameworkUiConfig.None -> null
        }

        val req = GenerationRequest(
            contractText = contractText,
            framework = ui.framework,
            baseUrl = ui.baseUrl,
            packageName = packageName,
            className = ui.className,
            outputNotes = ui.notes
        )

        val prompt = PromptBuilder.build(req)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val code = kotlinx.coroutines.runBlocking { provider.generateCode(prompt) }
                writeGenerated(project, file, ui.framework, ui.location, ui.className, code)
            } catch (e: Exception) {
                log.error("Test generation failed:", e)
                Notifications.error(project, "Test generation failed:", e.message ?: e.toString())
            }
        }
    }

    private fun writeGenerated(
        project: Project,
        contractFile: VirtualFile,
        framework: Framework,
        location: String,
        cls: String,
        code: String
    ) {
        val projectRoot = project.guessProjectDir()
            ?: throw IllegalStateException("Cannot resolve project root")

        val normalizedLocation = location
            .trim()
            .replace('\\', '/')
            .removePrefix("/")
            .removeSuffix("/")

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
}