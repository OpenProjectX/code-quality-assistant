package org.openprojectx.ai.plugin

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.JTextArea
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import java.awt.datatransfer.StringSelection
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
        val bgColor = Color(0x0F, 0x17, 0x2A)
        val fgColor = Color(0xE5, 0xED, 0xF7)
        val borderColor = Color(0x2A, 0x3A, 0x52)

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
        val extraRequirementField = JTextField().apply {
            background = Color.WHITE
            foreground = Color.BLACK
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            toolTipText = "Enter extra requirement and click Send"
        }
        val sendButton = JButton("Send")
        val clearButton = JButton("Clear")

        fun styledScrollPane(component: java.awt.Component): JBScrollPane =
            JBScrollPane(component).apply {
                viewport.background = bgColor
                background = bgColor
                border = BorderFactory.createLineBorder(borderColor)
            }

        fun buildFollowUpPrompt(snapshot: ContextBoxStateService.Snapshot, extraRequirement: String): String {
            return """
                You are continuing from previous AI Context Box output.

                Previous latest result:
                ${snapshot.latestResult}

                Extra requirement:
                $extraRequirement

                Provide an updated response only.
            """.trimIndent()
        }

        val panel = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JLabel("Context History"), BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(clearButton)
                }, BorderLayout.EAST)
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }, BorderLayout.NORTH)
            add(styledScrollPane(resultArea), BorderLayout.CENTER)
            add(JPanel(BorderLayout(8, 0)).apply {
                border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
                add(extraRequirementField, BorderLayout.CENTER)
                add(sendButton, BorderLayout.EAST)
                isOpaque = false
            }, BorderLayout.SOUTH)
            background = bgColor
            foreground = fgColor
        }

        fun render(snapshot: ContextBoxStateService.Snapshot) {
            resultArea.text = if (snapshot.history.isEmpty()) {
                "No result yet."
            } else {
                snapshot.history.joinToString("\n\n────────────\n\n")
            }
            resultArea.caretPosition = resultArea.document.length
        }

        clearButton.addActionListener {
            stateService.clearHistory()
        }
        sendButton.addActionListener {
            val extraRequirement = extraRequirementField.text.trim()
            if (extraRequirement.isBlank()) return@addActionListener
            val snapshot = stateService.snapshot()
            val prompt = buildFollowUpPrompt(snapshot, extraRequirement)
            sendButton.isEnabled = false

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Context Box Follow-up", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Calling LLM..."
                        val response = LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
                            val provider = LlmProviderFactory.create(settings)
                            runBlocking { provider.generateCode(prompt) }
                        }
                        ApplicationManager.getApplication().invokeLater {
                            stateService.recordFollowUp(extraRequirement, response)
                            extraRequirementField.text = ""
                            sendButton.isEnabled = true
                        }
                    } catch (ex: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            sendButton.isEnabled = true
                            Notifications.error(project, "Context Box Follow-up failed", ex.message ?: ex.toString())
                        }
                    }
                }
            })
        }

        render(stateService.snapshot())

        project.messageBus.connect(toolWindow.disposable).subscribe(
            ContextBoxStateService.TOPIC,
            ContextBoxListener { snapshot ->
                render(snapshot)
            }
        )

        val tabs = JTabbedPane().apply {
            addTab("Context", panel)
            addTab("Prompt Manager", createPromptManagerPanel(project, bgColor, fgColor, borderColor, commonFont))
            if (LlmSettingsLoader.loadSettingsModel(project).showLogTab) {
                addTab("Log", createLogPanel(bgColor, fgColor, borderColor, commonFont))
            }
        }

        val content = ContentFactory.getInstance().createContent(tabs, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private enum class PromptCategory(val label: String) {
        TEST("Test Generate"),
        COMMIT("Commit Generate"),
        BRANCH_DIFF("Branch Compare"),
        CODE_GENERATE("Code Generate"),
        CODE_REVIEW("Code Review");

        val globalCategoryKey: String
            get() = when (this) {
                TEST -> "test"
                COMMIT -> "commit"
                BRANCH_DIFF -> "branchDiff"
                CODE_GENERATE -> "codeGenerate"
                CODE_REVIEW -> "codeReview"
            }

        override fun toString(): String = label
    }

    private enum class PromptSort(val label: String) {
        TYPE("Sort by: Type"),
        NAME("Sort by: Name"),
        UPDATED("Sort by: Updated");

        override fun toString(): String = label
    }

    private data class PromptDefinition(
        val category: PromptCategory,
        val name: String,
        val content: String,
        val isGlobal: Boolean,
        val updatedText: String = "—"
    ) {
        val displayName: String
            get() = name.removePrefix("global/").replace(Regex("\\s*\\[[^]]+]$"), "")
    }

    private sealed class PromptListRow {
        data class All(val count: Int) : PromptListRow()
        data class CategoryHeader(val category: PromptCategory, val count: Int, val collapsed: Boolean) : PromptListRow()
        data class PromptRow(val prompt: PromptDefinition) : PromptListRow()
        data class AddPrompt(val category: PromptCategory) : PromptListRow()
        object CustomHeader : PromptListRow()
    }

    private fun categoryIcon(category: PromptCategory): String = when (category) {
        PromptCategory.TEST -> "⚗"
        PromptCategory.COMMIT -> "⌘"
        PromptCategory.BRANCH_DIFF -> "⑂"
        PromptCategory.CODE_GENERATE -> "</>"
        PromptCategory.CODE_REVIEW -> "▣"
    }

    private fun categoryColor(category: PromptCategory): Color = when (category) {
        PromptCategory.TEST -> Color(0xA7, 0x8B, 0xFA)
        PromptCategory.COMMIT -> Color(0xF5, 0x9E, 0x0B)
        PromptCategory.BRANCH_DIFF -> Color(0x38, 0xB2, 0xDF)
        PromptCategory.CODE_GENERATE -> Color(0x22, 0xC5, 0x5E)
        PromptCategory.CODE_REVIEW -> Color(0xFB, 0x71, 0x85)
    }

    private fun scopeColor(isGlobal: Boolean): Color =
        if (isGlobal) Color(0x60, 0xA5, 0xFA) else Color(0x34, 0xD3, 0x99)

    private fun createPromptManagerPanel(
        project: Project,
        bgColor: Color,
        fgColor: Color,
        borderColor: Color,
        commonFont: Font
    ): Component {
        val usage = ButtonUsageReportService.getInstance(project)
        val pageColor = Color(0x0F, 0x17, 0x2A)
        val surfaceColor = Color(0x11, 0x1C, 0x2F)
        val inputColor = Color(0x0B, 0x12, 0x20)
        val accentColor = Color(0x3B, 0x82, 0xF6)
        val mutedColor = Color(0x94, 0xA3, 0xB8)
        val cardColor = Color(0x14, 0x1F, 0x34)
        val designBorderColor = borderColor
        val promptFont = UIManager.getFont("EditorPane.font")
            ?.deriveFont(Font.PLAIN, 14f)
            ?: Font("JetBrains Mono", Font.PLAIN, 14)
        val listModel = DefaultListModel<PromptListRow>()
        val collapsedCategories = mutableSetOf<PromptCategory>()
        var selectedPrompt: PromptDefinition? = null

        val searchField = JTextField().apply {
            toolTipText = "Search prompts"
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(designBorderColor),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
        }
        val typeFilter = JComboBox(arrayOf("All Types") + PromptCategory.entries.map { it.label }.toTypedArray()).apply {
            background = inputColor
            foreground = fgColor
        }
        val sortField = JComboBox(PromptSort.entries.toTypedArray()).apply {
            background = inputColor
            foreground = fgColor
        }
        val promptList = JList(listModel).apply {
            font = commonFont
            background = surfaceColor
            foreground = fgColor
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectionBackground = accentColor
            selectionForeground = Color.WHITE
            fixedCellHeight = -1
            cellRenderer = PromptListCellRenderer(surfaceColor, fgColor, mutedColor, designBorderColor, accentColor, commonFont)
        }
        val categoryField = JComboBox(PromptCategory.entries.toTypedArray()).apply {
            background = inputColor
            foreground = fgColor
        }
        val nameField = JTextField().apply {
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(designBorderColor),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
        }
        val contentField = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            font = promptFont
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
        }
        val titleLabel = JLabel("Select a prompt").apply {
            foreground = fgColor
            font = commonFont.deriveFont(Font.PLAIN, 18f)
        }
        val scopeLabel = JLabel("—").apply { foreground = mutedColor }
        val detailsPanel = JPanel(GridBagLayout()).apply {
            background = surfaceColor
            border = BorderFactory.createLineBorder(designBorderColor)
        }
        val contentPreview = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = promptFont
            background = cardColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        val viewCards = JPanel(CardLayout()).apply { background = pageColor }

        fun mapForCategory(model: AiTestSettingsModel, category: PromptCategory): LinkedHashMap<String, String> {
            val text = when (category) {
                PromptCategory.TEST -> model.generationPromptProfilesYaml
                PromptCategory.COMMIT -> model.commitPromptProfilesYaml
                PromptCategory.BRANCH_DIFF -> model.branchDiffPromptProfilesYaml
                PromptCategory.CODE_GENERATE -> model.codeGeneratePromptProfilesYaml
                PromptCategory.CODE_REVIEW -> model.codeReviewPromptProfilesYaml
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

        fun mapsForModel(model: AiTestSettingsModel): Map<PromptCategory, LinkedHashMap<String, String>> = mapOf(
            PromptCategory.TEST to mapForCategory(model, PromptCategory.TEST),
            PromptCategory.COMMIT to mapForCategory(model, PromptCategory.COMMIT),
            PromptCategory.BRANCH_DIFF to mapForCategory(model, PromptCategory.BRANCH_DIFF),
            PromptCategory.CODE_GENERATE to mapForCategory(model, PromptCategory.CODE_GENERATE),
            PromptCategory.CODE_REVIEW to mapForCategory(model, PromptCategory.CODE_REVIEW)
        )

        fun updateModelFromMaps(
            model: AiTestSettingsModel,
            maps: Map<PromptCategory, LinkedHashMap<String, String>>,
            suppressed: List<String> = model.suppressedGlobalPrompts
        ): AiTestSettingsModel = model.copy(
            generationPromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.TEST)),
            commitPromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.COMMIT)),
            branchDiffPromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.BRANCH_DIFF)),
            codeGeneratePromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.CODE_GENERATE)),
            codeReviewPromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.CODE_REVIEW)),
            suppressedGlobalPrompts = suppressed.distinct().sorted()
        )

        fun updatedText(name: String): String {
            val match = Regex("\\[([^]]+)]$").find(name)?.groupValues?.get(1).orEmpty()
            return match.takeIf { it.isNotBlank() }?.replace('T', ' ')?.take(16) ?: "—"
        }

        fun allPrompts(): List<PromptDefinition> {
            val model = LlmSettingsLoader.loadSettingsModel(project)
            return PromptCategory.entries.flatMap { category ->
                mapForCategory(model, category).map { (name, content) ->
                    PromptDefinition(category, name, content, isGlobalPromptName(name), updatedText(name))
                }
            }
        }

        fun promptSuppressionKeys(prompt: PromptDefinition): List<String> =
            listOf(prompt.name, prompt.displayName)
                .filter { it.isNotBlank() }
                .distinct()
                .map { "${prompt.category.globalCategoryKey}:$it" }

        fun removePromptFromMap(map: MutableMap<String, String>, prompt: PromptDefinition) {
            val namesToRemove = setOf(prompt.name, prompt.displayName)
            map.keys
                .filter { it in namesToRemove || it.removePrefix("global/").replace(Regex("\\s*\\[[^]]+]$"), "") == prompt.displayName }
                .toList()
                .forEach { map.remove(it) }
        }

        fun applyPromptToDetails(prompt: PromptDefinition?) {
            selectedPrompt = prompt
            if (prompt == null) {
                titleLabel.text = "Select a prompt"
                titleLabel.foreground = fgColor
                scopeLabel.text = ""
                scopeLabel.toolTipText = null
                scopeLabel.foreground = mutedColor
                contentPreview.text = ""
                detailsPanel.removeAll()
                detailsPanel.revalidate()
                detailsPanel.repaint()
                return
            }
            titleLabel.text = prompt.displayName
            titleLabel.foreground = categoryColor(prompt.category)
            scopeLabel.text = if (prompt.isGlobal) "🌐" else "📁"
            scopeLabel.toolTipText = if (prompt.isGlobal) "Global" else "Local"
            scopeLabel.foreground = scopeColor(prompt.isGlobal)
            contentPreview.text = prompt.content
            contentPreview.caretPosition = 0
            detailsPanel.removeAll()

            fun addDetailRow(row: Int, label: String, value: String, valueColor: Color = fgColor) {
                val labelConstraints = GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    weightx = 0.0
                    fill = GridBagConstraints.HORIZONTAL
                }
                detailsPanel.add(JLabel(label).apply {
                    foreground = mutedColor
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(designBorderColor),
                        BorderFactory.createEmptyBorder(10, 12, 10, 12)
                    )
                }, labelConstraints)

                val valueConstraints = GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                }
                detailsPanel.add(JLabel(value).apply {
                    foreground = valueColor
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(designBorderColor),
                        BorderFactory.createEmptyBorder(10, 12, 10, 12)
                    )
                }, valueConstraints)
            }

            addDetailRow(0, "Name", prompt.displayName, categoryColor(prompt.category))
            addDetailRow(1, "Type", "${categoryIcon(prompt.category)}  ${prompt.category.label}", categoryColor(prompt.category))
            addDetailRow(2, "Scope", if (prompt.isGlobal) "🌐" else "📁", scopeColor(prompt.isGlobal))
            addDetailRow(3, "Updated Time", prompt.updatedText)
            detailsPanel.revalidate()
            detailsPanel.repaint()
        }

        fun showEditor(prompt: PromptDefinition?) {
            categoryField.selectedItem = prompt?.category ?: PromptCategory.TEST
            categoryField.isEnabled = prompt?.isGlobal != true
            nameField.isEditable = prompt?.isGlobal != true
            nameField.text = when {
                prompt == null -> ""
                prompt.isGlobal -> prompt.name
                else -> prompt.displayName
            }
            contentField.text = prompt?.content.orEmpty()
            (viewCards.layout as CardLayout).show(viewCards, "edit")
            nameField.requestFocusInWindow()
        }

        fun refreshList(select: PromptDefinition? = selectedPrompt) {
            listModel.removeAllElements()
            val query = searchField.text.trim().lowercase()
            val selectedType = typeFilter.selectedItem?.toString().orEmpty()
            val sort = sortField.selectedItem as? PromptSort ?: PromptSort.TYPE
            val prompts = allPrompts()
                .filter { query.isBlank() || it.displayName.lowercase().contains(query) || it.content.lowercase().contains(query) }
                .filter { selectedType == "All Types" || it.category.label == selectedType }
                .let { list ->
                    when (sort) {
                        PromptSort.TYPE -> list.sortedWith(compareBy<PromptDefinition> { it.category.ordinal }.thenBy { it.displayName })
                        PromptSort.NAME -> list.sortedBy { it.displayName }
                        PromptSort.UPDATED -> list.sortedByDescending { it.updatedText }
                    }
                }
            listModel.addElement(PromptListRow.All(prompts.size))
            PromptCategory.entries.forEach { category ->
                val categoryPrompts = prompts.filter { it.category == category }
                listModel.addElement(PromptListRow.CategoryHeader(category, categoryPrompts.size, category in collapsedCategories))
                if (category !in collapsedCategories) {
                    categoryPrompts.forEach { listModel.addElement(PromptListRow.PromptRow(it)) }
                    listModel.addElement(PromptListRow.AddPrompt(category))
                }
            }
            listModel.addElement(PromptListRow.CustomHeader)
            if (select != null) {
                val index = (0 until listModel.size()).firstOrNull {
                    val row = listModel.get(it)
                    row is PromptListRow.PromptRow && row.prompt.category == select.category && row.prompt.name == select.name
                } ?: -1
                if (index >= 0) {
                    promptList.selectedIndex = index
                } else {
                    promptList.clearSelection()
                }
            } else {
                promptList.clearSelection()
            }
            promptList.revalidate()
            promptList.repaint()
        }

        promptList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            when (val row = promptList.selectedValue) {
                is PromptListRow.All -> applyPromptToDetails(null)
                is PromptListRow.CategoryHeader -> {
                    if (row.category in collapsedCategories) collapsedCategories.remove(row.category) else collapsedCategories.add(row.category)
                    refreshList(null)
                }
                is PromptListRow.PromptRow -> {
                    applyPromptToDetails(row.prompt)
                    (viewCards.layout as CardLayout).show(viewCards, "view")
                }
                is PromptListRow.AddPrompt -> {
                    selectedPrompt = null
                    categoryField.isEnabled = true
                    nameField.isEditable = true
                    categoryField.selectedItem = row.category
                    nameField.text = ""
                    contentField.text = ""
                    (viewCards.layout as CardLayout).show(viewCards, "edit")
                }
                PromptListRow.CustomHeader, null -> Unit
            }
        }

        searchField.document.addDocumentListener(SimpleDocumentListener { refreshList(null) })
        typeFilter.addActionListener { refreshList(null) }
        sortField.addActionListener { refreshList(selectedPrompt) }

        fun compactActionButton(text: String, tooltip: String): JButton = JButton(text).apply {
            toolTipText = tooltip
            margin = Insets(1, 5, 1, 5)
            preferredSize = Dimension(28, 26)
            minimumSize = preferredSize
            font = commonFont.deriveFont(Font.PLAIN, 12f)
        }

        val saveButton = JButton("Save")
        val cancelButton = JButton("Cancel")
        val editButton = compactActionButton("✎", "Edit prompt")
        val duplicateButton = compactActionButton("⧉", "Duplicate prompt")
        val deleteButton = compactActionButton("🗑", "Delete or hide prompt")
        val refreshPromptsButton = compactActionButton("↻", "Refresh prompt list")
        val checkUpdateButton = JButton("☁ Check Update")
        val newPromptButton = JButton("+ New Prompt")
        val copyButton = JButton("⧉").apply {
            toolTipText = "Copy prompt content"
            margin = Insets(0, 0, 0, 0)
            preferredSize = Dimension(36, 30)
            minimumSize = preferredSize
            maximumSize = preferredSize
            font = commonFont.deriveFont(Font.PLAIN, 14f)
        }
        searchField.preferredSize = Dimension(searchField.preferredSize.width.coerceAtLeast(220), checkUpdateButton.preferredSize.height)
        searchField.minimumSize = Dimension(160, checkUpdateButton.preferredSize.height)

        fun promptUpdateMessage(status: LlmSettingsLoader.PromptUpdateStatus): String =
            "${status.message} Remote=${status.remoteCount}, LocalCache=${status.cachedCount}."

        fun setUpdateControlsEnabled(enabled: Boolean) {
            checkUpdateButton.isEnabled = enabled
        }

        fun handlePullUpdateStatus(status: LlmSettingsLoader.PromptUpdateStatus) {
            if (!status.configured) {
                Notifications.warn(project, "Prompt Manager", status.message)
                return
            }
            if (status.error) {
                Notifications.error(project, "Prompt Manager", status.message)
                return
            }
            refreshList(selectedPrompt)
            Notifications.info(project, "Prompt Manager", promptUpdateMessage(status))
        }

        fun performPullUpdate() {
            usage.record("context_box.prompt_manager.pull_update")
            setUpdateControlsEnabled(false)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Pull Prompt Updates", true) {
                private var status: LlmSettingsLoader.PromptUpdateStatus? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Pulling prompt updates..."
                    status = LlmSettingsLoader.pullBitbucketPromptUpdates(project)
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater {
                        setUpdateControlsEnabled(true)
                        status?.let { handlePullUpdateStatus(it) }
                    }
                }
            })
        }

        fun handleCheckUpdateStatus(status: LlmSettingsLoader.PromptUpdateStatus) {
            if (!status.configured) {
                Notifications.warn(project, "Prompt Manager", status.message)
                return
            }
            if (status.error) {
                Notifications.error(project, "Prompt Manager", status.message)
                return
            }
            val message = promptUpdateMessage(status)
            if (status.hasUpdates) {
                val choice = Messages.showYesNoDialog(project, "$message\n\nUpdate prompts now?", "Prompt Manager", "Update", "Later", null)
                if (choice == Messages.YES) performPullUpdate() else Notifications.info(project, "Prompt Manager", message)
            } else {
                Notifications.info(project, "Prompt Manager", message)
            }
        }

        checkUpdateButton.addActionListener {
            usage.record("context_box.prompt_manager.check_update")
            setUpdateControlsEnabled(false)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Check Prompt Updates", true) {
                private var status: LlmSettingsLoader.PromptUpdateStatus? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Checking prompt updates..."
                    status = LlmSettingsLoader.checkBitbucketPromptUpdates(project)
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater {
                        setUpdateControlsEnabled(true)
                        status?.let { handleCheckUpdateStatus(it) }
                    }
                }
            })
        }

        refreshPromptsButton.addActionListener {
            usage.record("context_box.prompt_manager.refresh")
            refreshList(selectedPrompt)
        }

        newPromptButton.addActionListener {
            usage.record("context_box.prompt_manager.new")
            selectedPrompt = null
            showEditor(null)
        }

        editButton.addActionListener {
            usage.record("context_box.prompt_manager.edit")
            showEditor(selectedPrompt)
        }

        duplicateButton.addActionListener {
            usage.record("context_box.prompt_manager.duplicate")
            val prompt = selectedPrompt ?: return@addActionListener
            selectedPrompt = null
            categoryField.isEnabled = true
            nameField.isEditable = true
            categoryField.selectedItem = prompt.category
            nameField.text = "${prompt.displayName} Copy"
            contentField.text = prompt.content
            (viewCards.layout as CardLayout).show(viewCards, "edit")
        }

        copyButton.addActionListener {
            val content = selectedPrompt?.content.orEmpty()
            if (content.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(content))
                Notifications.info(project, "Prompt Manager", "Prompt content copied.")
            }
        }

        saveButton.addActionListener {
            usage.record("context_box.prompt_manager.save")
            val current = selectedPrompt
            val category = if (current?.isGlobal == true) current.category else categoryField.selectedItem as? PromptCategory ?: PromptCategory.TEST
            val name = if (current?.isGlobal == true) current.name else nameField.text.trim()
            val content = contentField.text.trim()
            if (name.isBlank() || content.isBlank()) {
                Messages.showErrorDialog(project, "Prompt name and content are required.", "Prompt Manager")
                return@addActionListener
            }
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val maps = mapsForModel(model).mapValues { LinkedHashMap(it.value) }
            if (current != null) {
                if (current.isGlobal && (current.category != category || current.name != name)) {
                    Messages.showErrorDialog(project, "Global prompt name/category cannot be changed.", "Prompt Manager")
                    return@addActionListener
                }
                maps.getValue(current.category).remove(current.name)
            }
            val target = maps.getValue(category)
            if (target.containsKey(name) && (current == null || current.category != category || current.name != name)) {
                Messages.showErrorDialog(project, "Prompt name already exists in selected category.", "Prompt Manager")
                return@addActionListener
            }
            target[name] = content
            val suppressionKey = "${category.globalCategoryKey}:$name"
            val suppressed = if (isGlobalPromptName(name)) model.suppressedGlobalPrompts - suppressionKey else model.suppressedGlobalPrompts
            val updated = updateModelFromMaps(model, maps, suppressed)
            LlmSettingsLoader.saveSettingsModel(project, updated)
            val saved = PromptDefinition(category, name, content, isGlobalPromptName(name), updatedText(name))
            applyPromptToDetails(saved)
            refreshList(saved)
            (viewCards.layout as CardLayout).show(viewCards, "view")
        }

        deleteButton.addActionListener {
            usage.record("context_box.prompt_manager.delete")
            val prompt = selectedPrompt
            if (prompt == null) {
                Messages.showErrorDialog(project, "Please select a prompt first.", "Prompt Manager")
                return@addActionListener
            }
            val actionDescription = if (prompt.isGlobal) "hide this global prompt locally" else "delete this local prompt"
            val confirm = Messages.showYesNoDialog(
                project,
                "Are you sure you want to $actionDescription?",
                "Prompt Manager",
                "Delete",
                "Cancel",
                null
            )
            if (confirm != Messages.YES) return@addActionListener
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val maps = mapsForModel(model).mapValues { LinkedHashMap(it.value) }
            removePromptFromMap(maps.getValue(prompt.category), prompt)
            val suppressed = if (prompt.isGlobal) {
                model.suppressedGlobalPrompts + promptSuppressionKeys(prompt)
            } else {
                model.suppressedGlobalPrompts
            }
            LlmSettingsLoader.saveSettingsModel(project, updateModelFromMaps(model, maps, suppressed))
            selectedPrompt = null
            refreshList(null)
            applyPromptToDetails(null)
            (viewCards.layout as CardLayout).show(viewCards, "view")
            Notifications.info(project, "Prompt Manager", if (prompt.isGlobal) "Global prompt hidden locally." else "Prompt deleted.")
        }

        cancelButton.addActionListener {
            categoryField.isEnabled = true
            nameField.isEditable = true
            (viewCards.layout as CardLayout).show(viewCards, "view")
        }

        val viewPanel = JPanel(BorderLayout(0, 12)).apply {
            background = pageColor
            add(JPanel(BorderLayout()).apply {
                background = surfaceColor
                add(JLabel("Details").apply {
                    foreground = fgColor
                    font = commonFont.deriveFont(16f)
                    border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
                }, BorderLayout.NORTH)
                add(JBScrollPane(detailsPanel).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(8, 6)).apply {
                background = pageColor
                add(JLabel("Prompt Content").apply { foreground = fgColor; font = commonFont.deriveFont(16f) }, BorderLayout.WEST)
                add(copyButton, BorderLayout.EAST)
                add(JBScrollPane(contentPreview).apply {
                    preferredSize = Dimension(480, 360)
                    border = BorderFactory.createLineBorder(designBorderColor)
                    viewport.background = cardColor
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }

        val editPanel = JPanel(GridBagLayout()).apply {
            background = surfaceColor
            border = BorderFactory.createLineBorder(designBorderColor)
            val gbc = GridBagConstraints().apply {
                insets = Insets(6, 6, 6, 6)
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }
            fun addLabel(row: Int, text: String) {
                gbc.gridx = 0
                gbc.gridy = row
                gbc.weightx = 0.0
                add(JLabel(text).apply { foreground = mutedColor }, gbc)
            }
            addLabel(0, "Type")
            gbc.gridx = 1
            gbc.gridy = 0
            gbc.weightx = 1.0
            add(categoryField, gbc)
            addLabel(1, "Name")
            gbc.gridx = 1
            gbc.gridy = 1
            add(nameField, gbc)
            addLabel(2, "Content")
            gbc.gridx = 1
            gbc.gridy = 2
            gbc.fill = GridBagConstraints.BOTH
            gbc.weighty = 1.0
            add(JBScrollPane(contentField).apply { preferredSize = Dimension(480, 360) }, gbc)
            gbc.gridx = 1
            gbc.gridy = 3
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weighty = 0.0
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(saveButton)
                add(cancelButton)
            }, gbc)
        }
        viewCards.add(viewPanel, "view")
        viewCards.add(editPanel, "edit")

        val leftPanel = JPanel(BorderLayout(0, 8)).apply {
            background = pageColor
            preferredSize = Dimension(470, 600)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(JPanel(BorderLayout(8, 8)).apply {
                background = surfaceColor
                add(JLabel("Prompt Manager").apply { foreground = fgColor; font = commonFont.deriveFont(16f) }, BorderLayout.NORTH)
                add(searchField, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(checkUpdateButton)
                    add(newPromptButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(8, 8)).apply {
                background = pageColor
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(refreshPromptsButton)
                    add(typeFilter)
                    add(sortField)
                }, BorderLayout.NORTH)
                add(JBScrollPane(promptList).apply {
                    border = BorderFactory.createLineBorder(designBorderColor)
                    viewport.background = surfaceColor
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        val rightPanel = JPanel(BorderLayout(0, 12)).apply {
            background = pageColor
            border = BorderFactory.createEmptyBorder(18, 18, 18, 18)
            add(JPanel(BorderLayout(12, 0)).apply {
                background = pageColor
                add(JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                    add(titleLabel, BorderLayout.CENTER)
                    add(scopeLabel, BorderLayout.EAST)
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                    add(editButton)
                    add(duplicateButton)
                    add(deleteButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(viewCards, BorderLayout.CENTER)
        }

        refreshList(null)
        applyPromptToDetails(null)
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel).apply {
            resizeWeight = 0.38
            dividerSize = 1
            border = BorderFactory.createEmptyBorder()
            background = pageColor
        }
    }

    private inner class PromptListCellRenderer(
        private val bgColor: Color,
        private val fgColor: Color,
        private val mutedColor: Color,
        private val borderColor: Color,
        private val accentColor: Color,
        private val commonFont: Font
    ) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(8, 0)).apply {
                background = if (isSelected) accentColor else bgColor
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)
                )
            }
            fun label(text: String, color: Color = fgColor, size: Float = 13f): JLabel = JLabel(text).apply {
                foreground = if (isSelected) Color.WHITE else color
                font = commonFont.deriveFont(size)
            }
            when (value) {
                is PromptListRow.All -> {
                    panel.add(label("☁  All", fgColor, 15f), BorderLayout.WEST)
                    panel.add(label(value.count.toString(), mutedColor), BorderLayout.EAST)
                }
                is PromptListRow.CategoryHeader -> {
                    val arrow = if (value.collapsed) "›" else "⌃"
                    panel.add(label("${categoryIcon(value.category)}  ${value.category.label}", categoryColor(value.category), 15f), BorderLayout.WEST)
                    panel.add(label("${value.count}   $arrow", mutedColor), BorderLayout.EAST)
                }
                is PromptListRow.PromptRow -> {
                    val prompt = value.prompt
                    panel.border = BorderFactory.createEmptyBorder(6, 28, 6, 8)
                    panel.add(label("${categoryIcon(prompt.category)}  ${prompt.displayName}", categoryColor(prompt.category)), BorderLayout.CENTER)
                    panel.add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        background = if (isSelected) accentColor else bgColor
                        add(label(if (prompt.isGlobal) "🌐" else "📁", scopeColor(prompt.isGlobal), 12f))
                        if (prompt.updatedText != "—") {
                            add(label(prompt.updatedText, mutedColor, 12f))
                        }
                    }, BorderLayout.EAST)
                }
                is PromptListRow.AddPrompt -> {
                    panel.border = BorderFactory.createEmptyBorder(6, 28, 6, 8)
                    panel.add(label("＋  Add Prompt", mutedColor), BorderLayout.WEST)
                }
                PromptListRow.CustomHeader -> {
                    panel.add(label("⚙  Custom", fgColor, 15f), BorderLayout.WEST)
                    panel.add(label("0   ›", mutedColor), BorderLayout.EAST)
                }
                else -> panel.add(label(""), BorderLayout.WEST)
            }
            return panel
        }
    }

    private fun interface SimpleDocumentListener : javax.swing.event.DocumentListener {
        fun update()
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = update()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = update()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = update()
    }

    private fun createLogPanel(
        bgColor: Color,
        fgColor: Color,
        borderColor: Color,
        commonFont: Font
    ): JPanel {
        val logArea = JTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = commonFont
            background = bgColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }

        fun render() {
            logArea.text = RuntimeLogStore.snapshot().joinToString("\n")
            logArea.caretPosition = logArea.document.length
        }

        val refreshButton = JButton("Refresh").apply {
            addActionListener { render() }
        }
        val clearButton = JButton("Clear").apply {
            addActionListener {
                RuntimeLogStore.clear()
                render()
            }
        }

        render()

        return JPanel(BorderLayout()).apply {
            background = bgColor
            foreground = fgColor
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 6)).apply {
                isOpaque = false
                add(refreshButton)
                add(clearButton)
            }, BorderLayout.NORTH)
            add(JBScrollPane(logArea).apply {
                viewport.background = bgColor
                background = bgColor
                border = BorderFactory.createLineBorder(borderColor)
            }, BorderLayout.CENTER)
        }
    }

    private fun isGlobalPromptName(name: String): Boolean {
        return name.startsWith("[ADA]") || name.startsWith("[repo]") || name.startsWith("global/")
    }
}
