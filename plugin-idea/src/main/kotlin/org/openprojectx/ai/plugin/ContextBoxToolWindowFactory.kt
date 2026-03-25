package org.openprojectx.ai.plugin

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.UIManager

class ContextBoxToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val stateService = ContextBoxStateService.getInstance(project)

        val commonFont = UIManager.getFont("Label.font")
            ?.deriveFont(Font.PLAIN, 13f)
            ?: Font("SansSerif", Font.PLAIN, 13)
        val bgColor = Color(0x0D, 0x0D, 0x0D)
        val fgColor = Color(0xFF, 0xFF, 0xFF)
        val borderColor = Color(0x22, 0x22, 0x22)

        val resultArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = commonFont
            background = bgColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }

        fun styledScrollPane(component: java.awt.Component): JBScrollPane =
            JBScrollPane(component).apply {
                viewport.background = bgColor
                background = bgColor
                border = BorderFactory.createLineBorder(borderColor)
            }

        val panel = JPanel(BorderLayout()).apply {
            val tabs = JTabbedPane().apply {
                addTab("Results", styledScrollPane(resultArea))
                addTab("Prompts", styledScrollPane(buildPromptManagerPanel(project, commonFont, bgColor, fgColor, borderColor)))
                background = bgColor
                foreground = fgColor
                font = commonFont
            }
            add(tabs, BorderLayout.CENTER)
            background = bgColor
            foreground = fgColor
        }

        fun render(snapshot: ContextBoxStateService.Snapshot) {
            resultArea.text = snapshot.latestResult
            resultArea.caretPosition = 0
        }

        render(stateService.snapshot())

        project.messageBus.connect(toolWindow.disposable).subscribe(
            ContextBoxStateService.TOPIC,
            ContextBoxListener { snapshot ->
                render(snapshot)
            }
        )

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private data class PromptItem(
        val name: String,
        val area: JTextArea,
        val defaultValue: String
    )

    private fun buildPromptManagerPanel(
        project: Project,
        font: Font,
        bgColor: Color,
        fgColor: Color,
        borderColor: Color
    ): JPanel {
        val settings = LlmSettingsLoader.loadSettingsModel(project)
        val wrapperArea = promptTextArea(settings.generationPromptWrapper, font, bgColor, fgColor)
        val restArea = promptTextArea(settings.generationPromptRestAssured, font, bgColor, fgColor)
        val karateArea = promptTextArea(settings.generationPromptKarate, font, bgColor, fgColor)
        val commitArea = promptTextArea(settings.commitPrompt, font, bgColor, fgColor)
        val prArea = promptTextArea(settings.pullRequestPrompt, font, bgColor, fgColor)

        val items = listOf(
            PromptItem("Generation Wrapper", wrapperArea, AiPromptDefaults.GENERATION_WRAPPER),
            PromptItem("Rest Assured Rules", restArea, AiPromptDefaults.GENERATION_REST_ASSURED),
            PromptItem("Karate Rules", karateArea, AiPromptDefaults.GENERATION_KARATE),
            PromptItem("Commit Message", commitArea, AiPromptDefaults.COMMIT_MESSAGE),
            PromptItem("Pull Request", prArea, AiPromptDefaults.PULL_REQUEST)
        )

        val host = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bgColor
            foreground = fgColor
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }

        val hint = JLabel("Click prompt name to expand.").apply {
            foreground = fgColor
            font = font
        }

        val toolbar = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = true
            background = bgColor
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
        }
        val addCombo = JComboBox<String>()
        val addButton = JButton("Add")
        val saveButton = JButton("Save")
        val reloadButton = JButton("Reload")
        val hiddenNames = linkedSetOf<String>()

        val rowsByName = linkedMapOf<String, JPanel>()

        fun refreshAddCombo() {
            addCombo.removeAllItems()
            hiddenNames.forEach { addCombo.addItem(it) }
            addCombo.isEnabled = hiddenNames.isNotEmpty()
            addButton.isEnabled = hiddenNames.isNotEmpty()
        }

        fun addPromptRow(item: PromptItem) {
            val titleButton = JButton(item.name).apply {
                horizontalAlignment = javax.swing.SwingConstants.LEFT
            }
            val removeButton = JButton("Delete")
            val content = JBScrollPane(item.area).apply {
                viewport.background = bgColor
                background = bgColor
                border = BorderFactory.createLineBorder(borderColor)
                isVisible = false
            }
            val header = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = true
                background = bgColor
                add(titleButton, BorderLayout.CENTER)
                add(removeButton, BorderLayout.EAST)
            }
            val row = JPanel(BorderLayout(0, 6)).apply {
                isOpaque = true
                background = bgColor
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                    BorderFactory.createEmptyBorder(6, 0, 6, 0)
                )
                add(header, BorderLayout.NORTH)
                add(content, BorderLayout.CENTER)
            }

            titleButton.addActionListener {
                content.isVisible = !content.isVisible
                row.revalidate()
            }

            removeButton.addActionListener {
                item.area.text = item.defaultValue
                host.remove(row)
                rowsByName.remove(item.name)
                hiddenNames.add(item.name)
                refreshAddCombo()
                host.revalidate()
                host.repaint()
            }

            rowsByName[item.name] = row
            host.add(row)
        }

        addButton.addActionListener {
            val selected = addCombo.selectedItem as? String ?: return@addActionListener
            val item = items.firstOrNull { it.name == selected } ?: return@addActionListener
            hiddenNames.remove(selected)
            addPromptRow(item)
            refreshAddCombo()
            host.revalidate()
            host.repaint()
        }

        saveButton.addActionListener {
            val current = LlmSettingsLoader.loadSettingsModel(project)
            val updated = current.copy(
                generationPromptWrapper = wrapperArea.text,
                generationPromptRestAssured = restArea.text,
                generationPromptKarate = karateArea.text,
                commitPrompt = commitArea.text,
                pullRequestPrompt = prArea.text
            )
            LlmSettingsLoader.saveSettingsModel(project, updated)
            Notifications.info(project, "Prompt Manager", "Prompts saved.")
        }

        reloadButton.addActionListener {
            val latest = LlmSettingsLoader.loadSettingsModel(project)
            wrapperArea.text = latest.generationPromptWrapper
            restArea.text = latest.generationPromptRestAssured
            karateArea.text = latest.generationPromptKarate
            commitArea.text = latest.commitPrompt
            prArea.text = latest.pullRequestPrompt
            Notifications.info(project, "Prompt Manager", "Prompts reloaded.")
        }

        toolbar.add(addCombo, BorderLayout.CENTER)
        toolbar.add(JPanel(BorderLayout(6, 0)).apply {
            isOpaque = true
            background = bgColor
            add(addButton, BorderLayout.WEST)
            add(saveButton, BorderLayout.CENTER)
            add(reloadButton, BorderLayout.EAST)
        }, BorderLayout.EAST)

        host.add(hint)
        host.add(toolbar)
        items.forEach { addPromptRow(it) }
        refreshAddCombo()
        return host
    }

    private fun promptTextArea(text: String, font: Font, bgColor: Color, fgColor: Color): JTextArea =
        JTextArea(text, 8, 60).apply {
            lineWrap = true
            wrapStyleWord = true
            this.font = font
            background = bgColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }
}
