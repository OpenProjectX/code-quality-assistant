package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
//import com.intellij.openapi.actionSystem.DumbAware
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys

class GenerateCommitMessagePromptMenuAction : ActionGroup("Generate Commit Message (Choose Prompt)", true), DumbAware {
    init {
        templatePresentation.icon = OpenProjectXIcons.GenerateTests
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val profiles = LlmSettingsLoader.loadConfig(project).prompts.profiles.commitMessage.items
        if (profiles.isEmpty()) return emptyArray()

        return profiles.entries.map { (name, template) ->
            GenerateCommitMessageByPromptAction(name, template)
        }.toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }
}

private class GenerateCommitMessageByPromptAction(
    private val promptName: String,
    private val promptTemplate: String
) : AnAction(promptName), DumbAware {
    init {
        templatePresentation.icon = OpenProjectXIcons.GenerateTests
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessageUi = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return

        saveDefaultCommitPromptProfile(project, promptName)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Commit Message", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Collecting git diff..."
                    val diff = GitDiffProvider.getDiff(project)

                    if (diff.isBlank()) {
                        Notifications.error(project, "Generate Commit Message", "No local git changes found.")
                        return
                    }

                    indicator.text = "Calling LLM..."
                    val message = AiCommitService(project).generate(
                        diff = diff,
                        templateOverride = promptTemplate
                    )

                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        commitMessageUi.setCommitMessage(message.trim())
                    }
                } catch (ex: Exception) {
                    Notifications.error(
                        project,
                        "Generate Commit Message failed",
                        ex.message ?: ex.toString()
                    )
                }
            }
        })
    }

    private fun saveDefaultCommitPromptProfile(project: Project, selectedName: String) {
        val current = LlmSettingsLoader.loadSettingsModel(project)
        if (current.commitPromptProfileDefault == selectedName) return
        LlmSettingsLoader.saveSettingsModel(
            project,
            current.copy(commitPromptProfileDefault = selectedName)
        )
    }
}
