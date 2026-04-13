package org.openprojectx.ai.plugin

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.JTextArea
import javax.swing.UIManager
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

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
            add(styledScrollPane(resultArea), BorderLayout.CENTER)
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

        val tabs = JTabbedPane().apply {
            addTab("Context Box", panel)
            addTab("Prompt Manager", createPromptManagerPanel(project, bgColor, fgColor, borderColor, commonFont))
        }

        val content = ContentFactory.getInstance().createContent(tabs, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private enum class PromptCategory(val label: String) {
        TEST("Test"),
        COMMIT("Commit"),
        BRANCH_DIFF("Branch Diff");

        override fun toString(): String = label
    }

    private data class PromptItem(
        val category: PromptCategory,
        val name: String,
        val isGlobal: Boolean
    )

    private fun createPromptManagerPanel(
        project: Project,
        bgColor: Color,
        fgColor: Color,
        borderColor: Color,
        commonFont: Font
    ): JPanel {
        val listModel = DefaultListModel<PromptItem>()
        val promptList = JList(listModel).apply {
            font = commonFont
            background = bgColor
            foreground = fgColor
            selectionBackground = Color(0x1B, 0x3A, 0x57)
            selectionForeground = Color.WHITE
        }
        val categoryField = JComboBox(PromptCategory.entries.toTypedArray())
        val nameField = JTextField()
        val contentField = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            font = commonFont
        }

        fun mapForCategory(model: AiTestSettingsModel, category: PromptCategory): LinkedHashMap<String, String> {
            val text = when (category) {
                PromptCategory.TEST -> model.generationPromptProfilesYaml
                PromptCategory.COMMIT -> model.commitPromptProfilesYaml
                PromptCategory.BRANCH_DIFF -> model.branchDiffPromptProfilesYaml
            }
            val parsed = Yaml().load<Any?>(text) as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val result = linkedMapOf<String, String>()
            parsed.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString().orEmpty()
                if (key.isNotBlank() && value.isNotBlank()) result[key] = value
            }
            return LinkedHashMap(result)
        }

        fun dumpYaml(value: Map<String, String>): String {
            val options = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                indent = 2
                isPrettyFlow = true
            }
            return Yaml(options).dump(value).trimEnd()
        }

        fun refreshList(select: PromptItem? = null) {
            listModel.removeAllElements()
            val model = LlmSettingsLoader.loadSettingsModel(project)
            PromptCategory.entries.forEach { category ->
                val map = mapForCategory(model, category)
                val global = map.keys.firstOrNull()
                map.keys.forEach { name ->
                    listModel.addElement(PromptItem(category, name, name == global))
                }
            }
            if (select != null) {
                val index = (0 until listModel.size()).firstOrNull {
                    val item = listModel.get(it)
                    item.category == select.category && item.name == select.name
                } ?: -1
                if (index >= 0) promptList.selectedIndex = index
            }
        }

        fun selectedItem(): PromptItem? = promptList.selectedValue

        promptList.addListSelectionListener {
            val selected = selectedItem() ?: return@addListSelectionListener
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val value = mapForCategory(model, selected.category)[selected.name].orEmpty()
            categoryField.selectedItem = selected.category
            nameField.text = selected.name
            contentField.text = value
        }

        val newButton = javax.swing.JButton("New")
        val saveButton = javax.swing.JButton("Save")
        val deleteButton = javax.swing.JButton("Delete")

        newButton.addActionListener {
            promptList.clearSelection()
            categoryField.selectedItem = PromptCategory.TEST
            nameField.text = ""
            contentField.text = ""
        }

        saveButton.addActionListener {
            val category = categoryField.selectedItem as? PromptCategory ?: PromptCategory.TEST
            val name = nameField.text.trim()
            val content = contentField.text.trim()
            if (name.isBlank() || content.isBlank()) {
                Messages.showErrorDialog(project, "Prompt name and content are required.", "Prompt Manager")
                return@addActionListener
            }
            val selected = selectedItem()
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val test = mapForCategory(model, PromptCategory.TEST)
            val commit = mapForCategory(model, PromptCategory.COMMIT)
            val branch = mapForCategory(model, PromptCategory.BRANCH_DIFF)

            if (selected != null) {
                val target = when (selected.category) {
                    PromptCategory.TEST -> test
                    PromptCategory.COMMIT -> commit
                    PromptCategory.BRANCH_DIFF -> branch
                }
                if (selected.isGlobal && (selected.category != category || selected.name != name)) {
                    Messages.showErrorDialog(project, "Global prompt name/category cannot be changed.", "Prompt Manager")
                    return@addActionListener
                }
                target.remove(selected.name)
            }

            val finalTarget = when (category) {
                PromptCategory.TEST -> test
                PromptCategory.COMMIT -> commit
                PromptCategory.BRANCH_DIFF -> branch
            }
            finalTarget[name] = content

            val updated = model.copy(
                generationPromptProfilesYaml = dumpYaml(test),
                commitPromptProfilesYaml = dumpYaml(commit),
                branchDiffPromptProfilesYaml = dumpYaml(branch)
            )
            LlmSettingsLoader.saveSettingsModel(project, updated)
            refreshList(PromptItem(category, name, false))
        }

        deleteButton.addActionListener {
            val selected = selectedItem()
            if (selected == null) {
                Messages.showErrorDialog(project, "Please select a prompt first.", "Prompt Manager")
                return@addActionListener
            }
            if (selected.isGlobal) {
                Messages.showErrorDialog(project, "Global prompt cannot be deleted.", "Prompt Manager")
                return@addActionListener
            }
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val test = mapForCategory(model, PromptCategory.TEST)
            val commit = mapForCategory(model, PromptCategory.COMMIT)
            val branch = mapForCategory(model, PromptCategory.BRANCH_DIFF)
            when (selected.category) {
                PromptCategory.TEST -> test.remove(selected.name)
                PromptCategory.COMMIT -> commit.remove(selected.name)
                PromptCategory.BRANCH_DIFF -> branch.remove(selected.name)
            }
            val updated = model.copy(
                generationPromptProfilesYaml = dumpYaml(test),
                commitPromptProfilesYaml = dumpYaml(commit),
                branchDiffPromptProfilesYaml = dumpYaml(branch)
            )
            LlmSettingsLoader.saveSettingsModel(project, updated)
            refreshList()
            newButton.doClick()
        }

        val detail = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
            )
            background = bgColor
            foreground = fgColor

            val gbc = GridBagConstraints().apply {
                insets = Insets(4, 4, 4, 4)
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }
            gbc.gridx = 0; gbc.gridy = 0; add(JLabel("Type"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(categoryField, gbc)
            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; add(JLabel("Name"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(nameField, gbc)
            gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; add(JLabel("Content"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0
            add(JBScrollPane(contentField), gbc)
            gbc.gridx = 1; gbc.gridy = 3; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(newButton); add(saveButton); add(deleteButton)
                isOpaque = false
            }, gbc)
        }

        refreshList()

        return JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            background = bgColor
            add(JBScrollPane(promptList).apply { border = BorderFactory.createLineBorder(borderColor) }, BorderLayout.WEST)
            add(detail, BorderLayout.CENTER)
        }
    }
}
