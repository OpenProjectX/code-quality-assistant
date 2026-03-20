package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.VcsDataKeys
import java.awt.event.MouseEvent

class GenerateCommitMessageAction : AnAction("Generate Commit Message") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessageUi = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)

        if (commitMessageUi == null) {
            Notifications.error(project, "Generate Commit Message", "Commit message box is not available.")
            return
        }

        val selectedTemplate = selectTemplateIfNeeded(project, e)
        if (selectedTemplate == SelectionResult.Cancelled) {
            return
        }

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
                        templateOverride = (selectedTemplate as? SelectionResult.Selected)?.template
                    )

                    ApplicationManager.getApplication().invokeLater {
                        commitMessageUi.setCommitMessage(message.trim())
//                        commitMessageUi.focus()
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

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    private fun selectTemplateIfNeeded(project: com.intellij.openapi.project.Project, e: AnActionEvent): SelectionResult {
        val mouseEvent = e.inputEvent as? MouseEvent ?: return SelectionResult.Default
        if (!mouseEvent.isPopupTrigger && mouseEvent.button != MouseEvent.BUTTON3) return SelectionResult.Default

        val config = LlmSettingsLoader.loadConfig(project)
        val items = config.prompts.profiles.commitMessage.items.toList()
        if (items.isEmpty()) return SelectionResult.Default

        val names = items.map { it.first }.toTypedArray()
        val selected = com.intellij.openapi.ui.Messages.showChooseDialog(
            project,
            "Choose commit prompt profile",
            "Commit Prompt Profiles",
            names,
            config.prompts.profiles.commitMessage.selected,
            null
        )
        if (selected == -1) return SelectionResult.Cancelled
        return SelectionResult.Selected(items[selected].second)
    }

    private sealed interface SelectionResult {
        data object Default : SelectionResult
        data object Cancelled : SelectionResult
        data class Selected(val template: String) : SelectionResult
    }
}
