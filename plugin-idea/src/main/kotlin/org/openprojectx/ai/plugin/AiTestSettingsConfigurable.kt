package org.openprojectx.ai.plugin

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class AiTestSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private var panel: JPanel? = null
    private var yamlArea: JTextArea? = null
    private var pathLabel: JLabel? = null
    private var initialText: String = ""

    override fun getId(): String = "org.openprojectx.ai.plugin.settings"

    override fun getDisplayName(): String = "AI Test Generator"

    override fun createComponent(): JComponent {
        val area = JTextArea(30, 100)
        area.tabSize = 2
        yamlArea = area

        val label = JLabel()
        pathLabel = label

        val reloadButton = JButton("Reload YAML").apply {
            addActionListener { reset() }
        }
        val loginButton = JButton("Login Now").apply {
            addActionListener { LlmAuthSessionService.getInstance(project).loginNowWithFeedback() }
        }

        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(loginButton)
            add(reloadButton)
        }

        panel = JPanel(BorderLayout(0, 8)).apply {
            add(label, BorderLayout.NORTH)
            add(JScrollPane(area), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }

        reset()
        return panel as JPanel
    }

    override fun isModified(): Boolean = (yamlArea?.text ?: "") != initialText

    override fun apply() {
        val text = yamlArea?.text ?: return
        try {
            LlmSettingsLoader.writeConfigText(project, text)
            initialText = text
            updatePathLabel()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "AI Test Generator")
            throw e
        }
    }

    override fun reset() {
        val text = LlmSettingsLoader.readConfigText(project)
        yamlArea?.text = text
        initialText = text
        updatePathLabel()
    }

    override fun disposeUIResources() {
        panel = null
        yamlArea = null
        pathLabel = null
    }

    private fun updatePathLabel() {
        pathLabel?.text = "Project config: ${LlmSettingsLoader.configFilePath(project)}"
    }
}
