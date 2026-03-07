package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.core.GenerationRequest
import org.openprojectx.ai.plugin.core.PromptBuilder
import org.slf4j.LoggerFactory


class GenerateTestsService(private val project: Project) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun generate(ui: GenerateTestsDialog.UiResult, file: VirtualFile, contractText: String) {
        val framework = when (ui.frameworkIndex) {
            0 -> Framework.REST_ASSURED
            else -> Framework.KARATE
        }

        // Load settings from IDE settings or project config file (you wanted user code config)
        val settings = LlmSettingsLoader.load(project) // implement: read from .idea or project file
        val provider = LlmProviderFactory.create(settings)

        val req = GenerationRequest(
            contractText = contractText,
            framework = framework,
            baseUrl = ui.baseUrl,
            packageName = ui.location,
            className = ui.className,
            outputNotes = ui.notes
        )

        val prompt = PromptBuilder.build(req)

        // Run async (but return in this action; do not claim background “later”)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val code = kotlinx.coroutines.runBlocking { provider.generateCode(prompt) }
                writeGenerated(project, file, framework, ui.location, ui.className, code)
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
        // Simplest: create file under same directory as contract (or user-selected later)
        val dir = contractFile.parent

        val fileName = when (framework) {
            Framework.REST_ASSURED -> "$cls.java"
            Framework.KARATE -> "$cls.feature" // or split: feature + runner
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val existing = dir.findChild(fileName)
            val target = existing ?: dir.createChildData(this, fileName)
            target.setBinaryContent(code.toByteArray(Charsets.UTF_8))
        }

//        todo  call Notifications.notifyFileGenerated( )
//
    }
}