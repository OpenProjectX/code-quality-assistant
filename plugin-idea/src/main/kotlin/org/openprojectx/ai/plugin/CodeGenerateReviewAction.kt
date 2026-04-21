package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.runBlocking
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class CodeGenerateReviewAction : AnAction("Code Generate & Review"), DumbAware {

    init {
        templatePresentation.icon = OpenProjectXIcons.GenerateTests
    }

    override fun update(e: AnActionEvent) {
        val selected = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText.orEmpty()
        e.presentation.isEnabledAndVisible = e.project != null && selected.isNotBlank()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedCode = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText.orEmpty().trim()
        if (selectedCode.isBlank()) {
            Notifications.warn(project, "Code Generate & Review", "Please select code first.")
            return
        }

        val config = LlmSettingsLoader.loadConfig(project)
        val promptOptions = buildList {
            config.prompts.profiles.codeGenerate.items.forEach { (name, template) ->
                add(PromptOption(category = PromptCategory.CODE_GENERATE, name = name, template = template))
            }
            config.prompts.profiles.codeReview.items.forEach { (name, template) ->
                add(PromptOption(category = PromptCategory.CODE_REVIEW, name = name, template = template))
            }
        }
        if (promptOptions.isEmpty()) {
            Notifications.warn(project, "Code Generate & Review", "No prompts configured in Prompt Manager.")
            return
        }

        val dialog = CodeGenerateReviewDialog(project, promptOptions)
        if (!dialog.showAndGet()) return
        val selectedPrompt = dialog.selectedPrompt() ?: return
        val extraRequirements = dialog.extraRequirements().ifBlank { "None" }

        savePromptSelection(project, selectedPrompt)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Code Generate & Review", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Calling LLM..."
                    val provider = LlmProviderFactory.create(LlmSettingsLoader.load(project))
                    val finalPrompt = AiPromptDefaults.render(
                        selectedPrompt.template,
                        mapOf(
                            "selectedCode" to selectedCode,
                            "extraRequirements" to extraRequirements
                        )
                    )
                    val response = runBlocking { provider.generateCode(finalPrompt) }
                    ApplicationManager.getApplication().invokeLater {
                        ContextBoxStateService.getInstance(project).recordCodePromptResult(
                            promptType = selectedPrompt.category.label,
                            promptName = selectedPrompt.name,
                            result = response
                        )
                        ToolWindowManager.getInstance(project).getToolWindow("AI Context Box")?.show(null)
                    }
                } catch (ex: Exception) {
                    Notifications.error(project, "Code Generate & Review failed", ex.message ?: ex.toString())
                }
            }
        })
    }

    private fun savePromptSelection(project: com.intellij.openapi.project.Project, option: PromptOption) {
        val current = LlmSettingsLoader.loadSettingsModel(project)
        when (option.category) {
            PromptCategory.CODE_GENERATE -> {
                if (current.codeGeneratePromptProfileDefault == option.name) return
                LlmSettingsLoader.saveSettingsModel(project, current.copy(codeGeneratePromptProfileDefault = option.name))
            }
            PromptCategory.CODE_REVIEW -> {
                if (current.codeReviewPromptProfileDefault == option.name) return
                LlmSettingsLoader.saveSettingsModel(project, current.copy(codeReviewPromptProfileDefault = option.name))
            }
        }
    }

    enum class PromptCategory(val label: String) {
        CODE_GENERATE("Code Generate"),
        CODE_REVIEW("Code Review")
    }

    data class PromptOption(
        val category: PromptCategory,
        val name: String,
        val template: String
    ) {
        override fun toString(): String = "[${category.label}] $name"
    }
}

private class CodeGenerateReviewDialog(
    project: com.intellij.openapi.project.Project,
    private val promptOptions: List<CodeGenerateReviewAction.PromptOption>
) : DialogWrapper(project) {

    private val promptCombo = JComboBox(promptOptions.toTypedArray())
    private val requirementArea = JTextArea(6, 80).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Code Generate & Review"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Prompt"))
            add(promptCombo)
            add(JLabel("Extra Requirements (optional)"))
            add(JScrollPane(requirementArea))
        }
    }

    fun selectedPrompt(): CodeGenerateReviewAction.PromptOption? =
        promptCombo.selectedItem as? CodeGenerateReviewAction.PromptOption

    fun extraRequirements(): String = requirementArea.text.trim()
}
