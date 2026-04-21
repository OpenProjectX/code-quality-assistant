package org.openprojectx.ai.plugin

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.openprojectx.ai.plugin.core.Framework
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class AiTestSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable {
    private var rootPanel: JPanel? = null
    private var pathLabel: JLabel? = null

    private lateinit var providerField: JComboBox<String>
    private lateinit var modelField: JTextField
    private lateinit var endpointField: JTextField
    private lateinit var timeoutField: JTextField
    private lateinit var apiKeyField: JPasswordField
    private lateinit var apiKeyEnvField: JTextField
    private lateinit var httpDisableTlsVerification: JCheckBox

    private lateinit var llmTemplateEnabled: JCheckBox
    private lateinit var llmTemplateMethod: JComboBox<String>
    private lateinit var llmTemplateUrl: JTextField
    private lateinit var llmTemplateHeaders: JTextArea
    private lateinit var llmTemplateBody: JTextArea
    private lateinit var llmTemplateResponsePath: JTextField
    private lateinit var llmTemplatePanel: JPanel
    private lateinit var llmTemplateCardPanel: JPanel

    private lateinit var loginEnabled: JCheckBox
    private lateinit var loginMethod: JComboBox<String>
    private lateinit var loginUrl: JTextField
    private lateinit var loginHeaders: JTextArea
    private lateinit var loginBody: JTextArea
    private lateinit var loginResponsePath: JTextField
    private lateinit var loginPanel: JPanel
    private lateinit var loginCardPanel: JPanel

    private lateinit var defaultFrameworkField: JComboBox<Framework>
    private lateinit var defaultClassNameField: JTextField
    private lateinit var defaultBaseUrlField: JTextField
    private lateinit var defaultNotesField: JTextArea
    private lateinit var commonLocationField: JTextField
    private lateinit var restAssuredLocationField: JTextField
    private lateinit var restAssuredPackageNameField: JTextField
    private lateinit var karateLocationField: JTextField
    private lateinit var generationPromptWrapperField: JTextArea
    private lateinit var generationPromptRestAssuredField: JTextArea
    private lateinit var generationPromptKarateField: JTextArea
    private lateinit var commitPromptField: JTextArea
    private lateinit var pullRequestPromptField: JTextArea
    private lateinit var branchDiffPromptField: JTextArea
    private lateinit var generationPromptProfileDefaultField: JTextField
    private lateinit var generationPromptProfilesYamlField: JTextArea
    private lateinit var commitPromptProfileDefaultField: JTextField
    private lateinit var commitPromptProfilesYamlField: JTextArea
    private lateinit var branchDiffPromptProfileDefaultField: JTextField
    private lateinit var branchDiffPromptProfilesYamlField: JTextArea
    private lateinit var codeGeneratePromptProfileDefaultField: JTextField
    private lateinit var codeGeneratePromptProfilesYamlField: JTextArea
    private lateinit var codeReviewPromptProfileDefaultField: JTextField
    private lateinit var codeReviewPromptProfilesYamlField: JTextArea
    private lateinit var bitbucketPromptRepoEnabledField: JCheckBox
    private lateinit var bitbucketPromptRepoUrlField: JTextField
    private lateinit var bitbucketPromptRepoBranchField: JTextField
    private lateinit var bitbucketPromptRepoTokenField: JPasswordField
    private lateinit var promptTypeField: JComboBox<PromptCategory>
    private lateinit var promptNameField: JTextField
    private lateinit var promptListPanel: JPanel
    private lateinit var promptContentField: JTextArea

    private var initialState: AiTestSettingsModel = AiTestSettingsModel()
    private var selectedPromptSelection: PromptSelection? = null

    private enum class PromptCategory(val label: String) {
        TEST("Test"),
        COMMIT("Commit"),
        BRANCH_DIFF("Branch Diff"),
        CODE_GENERATE("Code Generate"),
        CODE_REVIEW("Code Review");

        override fun toString(): String = label
    }

    private data class PromptSelection(
        val category: PromptCategory,
        val name: String,
        val isGlobal: Boolean
    )

    override fun getId(): String = "org.openprojectx.ai.plugin.settings"

    override fun getDisplayName(): String = "AI Test Generator"

    override fun createComponent(): JComponent {
        val usage = ButtonUsageReportService.getInstance(project)
        providerField = JComboBox(arrayOf("openai-compatible", "template"))
        modelField = JTextField()
        endpointField = JTextField()
        timeoutField = JTextField()
        apiKeyField = JPasswordField()
        apiKeyEnvField = JTextField()
        httpDisableTlsVerification = JCheckBox("Disable TLS certificate verification (insecure, use only on trusted networks)")

        llmTemplateEnabled = JCheckBox("Use template-based LLM request")
        llmTemplateMethod = methodCombo()
        llmTemplateUrl = JTextField()
        llmTemplateHeaders = textArea(5)
        llmTemplateBody = textArea(10)
        llmTemplateResponsePath = JTextField()
        llmTemplatePanel = templatePanel(
            method = llmTemplateMethod,
            url = llmTemplateUrl,
            headers = llmTemplateHeaders,
            body = llmTemplateBody,
            responsePath = llmTemplateResponsePath,
            title = "LLM Request Template"
        )

        loginEnabled = JCheckBox("Enable login flow for API key retrieval")
        loginMethod = methodCombo()
        loginUrl = JTextField()
        loginHeaders = textArea(5)
        loginBody = textArea(8)
        loginResponsePath = JTextField()
        loginPanel = templatePanel(
            method = loginMethod,
            url = loginUrl,
            headers = loginHeaders,
            body = loginBody,
            responsePath = loginResponsePath,
            title = "Login Request Template"
        )

        defaultFrameworkField = JComboBox(Framework.entries.toTypedArray())
        defaultClassNameField = JTextField()
        defaultBaseUrlField = JTextField()
        defaultNotesField = textArea(5)
        commonLocationField = JTextField()
        restAssuredLocationField = JTextField()
        restAssuredPackageNameField = JTextField()
        karateLocationField = JTextField()
        generationPromptWrapperField = textArea(10)
        generationPromptRestAssuredField = textArea(12)
        generationPromptKarateField = textArea(10)
        commitPromptField = textArea(12)
        pullRequestPromptField = textArea(14)
        branchDiffPromptField = textArea(12)
        generationPromptProfileDefaultField = JTextField()
        generationPromptProfilesYamlField = textArea(12)
        commitPromptProfileDefaultField = JTextField()
        commitPromptProfilesYamlField = textArea(12)
        branchDiffPromptProfileDefaultField = JTextField()
        branchDiffPromptProfilesYamlField = textArea(12)
        codeGeneratePromptProfileDefaultField = JTextField()
        codeGeneratePromptProfilesYamlField = textArea(12)
        codeReviewPromptProfileDefaultField = JTextField()
        codeReviewPromptProfilesYamlField = textArea(12)
        bitbucketPromptRepoEnabledField = JCheckBox("Enable Bitbucket prompt repository")
        bitbucketPromptRepoUrlField = JTextField()
        bitbucketPromptRepoBranchField = JTextField("main")
        bitbucketPromptRepoTokenField = JPasswordField()
        promptTypeField = JComboBox(PromptCategory.entries.toTypedArray())
        promptNameField = JTextField()
        promptContentField = textArea(8)

        llmTemplateEnabled.addActionListener { toggleTemplateCards() }
        loginEnabled.addActionListener { toggleTemplateCards() }

        val tabs = JTabbedPane().apply {
            addTab("LLM", llmTab())
            addTab("Prompts", promptsTab())
            addTab("Login", loginTab())
            addTab("Generation", generationTab())
        }

        val toolbar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(12, 12, 8, 12),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createEtchedBorder(),
                    BorderFactory.createEmptyBorder(10, 12, 10, 12)
                )
            )
            add(JLabel("Configure LLM access, login automation, and generation defaults.").apply {
                horizontalAlignment = SwingConstants.LEFT
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                add(JButton("Login Now").apply {
                    addActionListener {
                        usage.record("settings.toolbar.login_now")
                        if (saveCurrentState()) {
                            LlmAuthSessionService.getInstance(project).loginNowWithFeedback()
                        }
                    }
                })
                add(JButton("Reload").apply {
                    addActionListener {
                        usage.record("settings.toolbar.reload")
                        reset()
                    }
                })
            }, BorderLayout.EAST)
        }

        pathLabel = JLabel()

        rootPanel = JPanel(BorderLayout(0, 8)).apply {
            add(toolbar, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
                add(pathLabel, BorderLayout.WEST)
            }, BorderLayout.SOUTH)
            preferredSize = Dimension(980, 760)
        }

        reset()
        return rootPanel as JPanel
    }

    override fun isModified(): Boolean = collectState() != initialState

    override fun apply() {
        val state = collectState()
        validate(state)
        LlmSettingsLoader.saveSettingsModel(project, state)
        initialState = state
        updatePathLabel()
    }

    override fun reset() {
        val state = LlmSettingsLoader.loadSettingsModel(project)
        applyState(state)
        initialState = state
        updatePathLabel()
    }

    override fun disposeUIResources() {
        rootPanel = null
        pathLabel = null
    }

    private fun llmTab(): JComponent = JScrollPane(JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(formSection("Provider", listOf(
            "Provider" to providerField,
            "Model" to modelField,
            "Endpoint" to endpointField,
            "Timeout (seconds)" to timeoutField
        )))
        add(formSection("Credentials", listOf(
            "Direct API key" to apiKeyField,
            "API key env var" to apiKeyEnvField
        )))
        add(formSection("HTTP", listOf(
            "" to httpDisableTlsVerification
        )))
        add(sectionWithToggle(llmTemplateEnabled, llmTemplatePanel).also { llmTemplateCardPanel = it })
    }).apply { border = BorderFactory.createEmptyBorder() }

    private fun loginTab(): JComponent = JScrollPane(JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(infoBanner("Configure a pre-login request that exchanges username/password for an API key using JSONPath extraction."))
        add(sectionWithToggle(loginEnabled, loginPanel).also { loginCardPanel = it })
    }).apply { border = BorderFactory.createEmptyBorder() }

    private fun generationTab(): JComponent = JScrollPane(JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(formSection("Defaults", listOf(
            "Framework" to defaultFrameworkField,
            "Class name" to defaultClassNameField,
            "Base URL" to defaultBaseUrlField,
            "Notes" to JScrollPane(defaultNotesField)
        )))
        add(formSection("Common Output", listOf(
            "Shared location" to commonLocationField
        )))
        add(formSection("Rest Assured", listOf(
            "Location" to restAssuredLocationField,
            "Package name" to restAssuredPackageNameField
        )))
        add(formSection("Karate", listOf(
            "Location" to karateLocationField
        )))
    }).apply { border = BorderFactory.createEmptyBorder() }

    private fun promptsTab(): JComponent = JScrollPane(JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(infoBanner("Built-in prompts remain the defaults. Edit any field below to override the default template saved in .ai-test.yaml."))
        add(formSection("Generation Wrapper", listOf(
            "Template" to JScrollPane(generationPromptWrapperField)
        )))
        add(formSection("Generation Rules", listOf(
            "Rest Assured rules" to JScrollPane(generationPromptRestAssuredField),
            "Karate rules" to JScrollPane(generationPromptKarateField)
        )))
        add(formSection("AI Actions", listOf(
            "Commit message prompt" to JScrollPane(commitPromptField),
            "Branch diff summary prompt" to JScrollPane(branchDiffPromptField),
            "Pull request prompt" to JScrollPane(pullRequestPromptField)
        )))
        add(formSection("Bitbucket Prompt Repo (Global Prompts)", listOf(
            "" to bitbucketPromptRepoEnabledField,
            "Repo URL" to bitbucketPromptRepoUrlField,
            "Branch" to bitbucketPromptRepoBranchField,
            "Token / PAT" to bitbucketPromptRepoTokenField
        )))
        add(unifiedPromptManagerSection())
    }).apply { border = BorderFactory.createEmptyBorder() }

    private fun unifiedPromptManagerSection(): JComponent {
        val clearButton = JButton("New / Clear")
        val saveButton = JButton("Save")
        val deleteButton = JButton("Delete")
        promptListPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val promptListScrollPane = JScrollPane(promptListPanel).apply {
            preferredSize = Dimension(640, 180)
            minimumSize = Dimension(320, 140)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        clearButton.addActionListener {
            ButtonUsageReportService.getInstance(project).record("settings.prompt_manager.new_clear")
            prepareNewPromptInput()
        }
        saveButton.addActionListener {
            ButtonUsageReportService.getInstance(project).record("settings.prompt_manager.save")
            savePromptProfile()
        }
        deleteButton.addActionListener {
            ButtonUsageReportService.getInstance(project).record("settings.prompt_manager.delete")
            deletePromptProfile()
        }

        refreshPromptManager()

        return formSection("Prompt Manager", listOf(
            "Prompt list (name + category)" to promptListScrollPane,
            "Prompt type" to promptTypeField,
            "Prompt name" to promptNameField,
            "Prompt content" to JScrollPane(promptContentField),
            "Action" to JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(clearButton)
                add(saveButton)
                add(deleteButton)
            }
        ))
    }

    private fun savePromptProfile() {
        val category = selectedPromptCategory()
        val name = promptNameField.text.trim()
        val content = promptContentField.text.trim()
        if (name.isBlank() || content.isBlank()) {
            Messages.showErrorDialog(project, "Prompt name and content are required.", "AI Test Generator")
            return
        }

        val maps = mutableMapsByCategory()
        val currentSelection = selectedPromptSelection
        if (currentSelection != null) {
            if (currentSelection.isGlobal && (currentSelection.category != category || currentSelection.name != name)) {
                Messages.showErrorDialog(project, "Global prompt name/category cannot be changed.", "AI Test Generator")
                return
            }
            maps[currentSelection.category]?.remove(currentSelection.name)
        }

        val targetMap = maps.getValue(category)
        if (targetMap.containsKey(name) && (currentSelection == null || currentSelection.name != name || currentSelection.category != category)) {
            Messages.showErrorDialog(project, "Prompt name already exists in selected category.", "AI Test Generator")
            return
        }
        targetMap[name] = content
        applyMapsByCategory(maps)
        defaultFieldByCategory(category).takeIf { it.text.isBlank() }?.text = name
        refreshPromptManager(selectCategory = category, selectName = name)
    }

    private fun deletePromptProfile() {
        val selection = selectedPromptSelection
        if (selection == null) {
            Messages.showErrorDialog(project, "Select a prompt before deleting.", "AI Test Generator")
            return
        }
        if (selection.isGlobal) {
            Messages.showErrorDialog(project, "Global prompt cannot be deleted.", "AI Test Generator")
            return
        }

        val maps = mutableMapsByCategory()
        val map = maps.getValue(selection.category)
        map.remove(selection.name)
        if (map.isEmpty()) {
            Messages.showErrorDialog(project, "At least one prompt must remain in this category.", "AI Test Generator")
            return
        }
        applyMapsByCategory(maps)

        val defaultField = defaultFieldByCategory(selection.category)
        if (defaultField.text.trim() == selection.name) {
            defaultField.text = map.keys.first()
        }
        prepareNewPromptInput()
        refreshPromptManager(selectCategory = selection.category, selectName = null)
    }

    private fun refreshPromptManager(selectCategory: PromptCategory? = null, selectName: String? = null) {
        val maps = mutableMapsByCategory()
        val targetCategory = selectCategory ?: selectedPromptCategory()
        val targetMap = maps.getValue(targetCategory)
        val resolvedSelection = selectName?.takeIf { targetMap.containsKey(it) }?.let {
            PromptSelection(
                category = targetCategory,
                name = it,
                isGlobal = targetMap.keys.firstOrNull() == it
            )
        }
        selectedPromptSelection = resolvedSelection
        promptListPanel.removeAll()

        val group = ButtonGroup()
        PromptCategory.entries.forEach { category ->
            val map = maps.getValue(category)
            map.keys.forEach { name ->
                val isGlobal = isGlobalPromptName(name)
                val checkbox = JCheckBox(
                    if (isGlobal) "🌐 $name  [${category.label}]  (Global)" else "📄 $name  [${category.label}]"
                ).apply {
                    isSelected = resolvedSelection?.category == category && resolvedSelection.name == name
                    addActionListener {
                        selectedPromptSelection = PromptSelection(category, name, isGlobal)
                        loadSelectedPromptContent()
                    }
                    if (isGlobal) {
                        font = font.deriveFont(font.style or java.awt.Font.BOLD)
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(java.awt.Color(70, 130, 180)),
                            BorderFactory.createEmptyBorder(2, 4, 2, 4)
                        )
                    }
                }
                group.add(checkbox)
                promptListPanel.add(checkbox)
            }
        }

        if (resolvedSelection == null) {
            prepareNewPromptInput()
        } else {
            loadSelectedPromptContent()
        }
        promptListPanel.revalidate()
        promptListPanel.repaint()
    }

    private fun loadSelectedPromptContent() {
        val selection = selectedPromptSelection
        if (selection == null) {
            promptNameField.text = ""
            promptContentField.text = ""
            return
        }
        promptTypeField.selectedItem = selection.category
        promptNameField.text = selection.name
        promptContentField.text = promptMapByCategory(selection.category)[selection.name].orEmpty()
    }

    private fun prepareNewPromptInput() {
        selectedPromptSelection = null
        promptTypeField.selectedItem = PromptCategory.TEST
        promptNameField.text = ""
        promptContentField.text = ""
        promptContentField.requestFocusInWindow()
    }

    private fun mutableMapsByCategory(): MutableMap<PromptCategory, LinkedHashMap<String, String>> {
        return mutableMapOf(
            PromptCategory.TEST to LinkedHashMap(promptMapByCategory(PromptCategory.TEST)),
            PromptCategory.COMMIT to LinkedHashMap(promptMapByCategory(PromptCategory.COMMIT)),
            PromptCategory.BRANCH_DIFF to LinkedHashMap(promptMapByCategory(PromptCategory.BRANCH_DIFF)),
            PromptCategory.CODE_GENERATE to LinkedHashMap(promptMapByCategory(PromptCategory.CODE_GENERATE)),
            PromptCategory.CODE_REVIEW to LinkedHashMap(promptMapByCategory(PromptCategory.CODE_REVIEW))
        )
    }

    private fun applyMapsByCategory(maps: Map<PromptCategory, LinkedHashMap<String, String>>) {
        updatePromptYaml(PromptCategory.TEST, maps.getValue(PromptCategory.TEST))
        updatePromptYaml(PromptCategory.COMMIT, maps.getValue(PromptCategory.COMMIT))
        updatePromptYaml(PromptCategory.BRANCH_DIFF, maps.getValue(PromptCategory.BRANCH_DIFF))
        updatePromptYaml(PromptCategory.CODE_GENERATE, maps.getValue(PromptCategory.CODE_GENERATE))
        updatePromptYaml(PromptCategory.CODE_REVIEW, maps.getValue(PromptCategory.CODE_REVIEW))
    }

    private fun selectedPromptCategory(): PromptCategory =
        promptTypeField.selectedItem as? PromptCategory ?: PromptCategory.TEST

    private fun promptMapByCategory(category: PromptCategory): Map<String, String> {
        return when (category) {
            PromptCategory.TEST -> parseYamlMap(generationPromptProfilesYamlField.text)
            PromptCategory.COMMIT -> parseYamlMap(commitPromptProfilesYamlField.text)
            PromptCategory.BRANCH_DIFF -> parseYamlMap(branchDiffPromptProfilesYamlField.text)
            PromptCategory.CODE_GENERATE -> parseYamlMap(codeGeneratePromptProfilesYamlField.text)
            PromptCategory.CODE_REVIEW -> parseYamlMap(codeReviewPromptProfilesYamlField.text)
        }
    }

    private fun updatePromptYaml(category: PromptCategory, value: Map<String, String>) {
        when (category) {
            PromptCategory.TEST -> generationPromptProfilesYamlField.text = dumpYamlMap(value)
            PromptCategory.COMMIT -> commitPromptProfilesYamlField.text = dumpYamlMap(value)
            PromptCategory.BRANCH_DIFF -> branchDiffPromptProfilesYamlField.text = dumpYamlMap(value)
            PromptCategory.CODE_GENERATE -> codeGeneratePromptProfilesYamlField.text = dumpYamlMap(value)
            PromptCategory.CODE_REVIEW -> codeReviewPromptProfilesYamlField.text = dumpYamlMap(value)
        }
    }

    private fun defaultFieldByCategory(category: PromptCategory): JTextField {
        return when (category) {
            PromptCategory.TEST -> generationPromptProfileDefaultField
            PromptCategory.COMMIT -> commitPromptProfileDefaultField
            PromptCategory.BRANCH_DIFF -> branchDiffPromptProfileDefaultField
            PromptCategory.CODE_GENERATE -> codeGeneratePromptProfileDefaultField
            PromptCategory.CODE_REVIEW -> codeReviewPromptProfileDefaultField
        }
    }

    private fun formSection(title: String, rows: List<Pair<String, JComponent>>): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(title),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        )

        val gbc = GridBagConstraints().apply {
            insets = Insets(6, 6, 6, 6)
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            weightx = 1.0
        }

        rows.forEachIndexed { index, (label, component) ->
            gbc.gridx = 0
            gbc.gridy = index
            gbc.weightx = 0.0
            panel.add(JLabel(label), gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(component, gbc)
        }

        gbc.gridx = 0
        gbc.gridy = rows.size
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height + 8)
        return panel
    }

    private fun templatePanel(
        method: JComboBox<String>,
        url: JTextField,
        headers: JTextArea,
        body: JTextArea,
        responsePath: JTextField,
        title: String
    ): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(formSection(title, listOf(
            "Method" to method,
            "URL" to url,
            "Response JSONPath" to responsePath,
            "Headers (one per line)" to JScrollPane(headers),
            "Body template" to JScrollPane(body)
        )))
    }

    private fun sectionWithToggle(toggle: JCheckBox, content: JComponent): JPanel {
        val cardPanel = JPanel(CardLayout()).apply {
            add(JPanel(), "off")
            add(content, "on")
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 0, 0, 0),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)
            )
            add(toggle)
            add(cardPanel)
        }
    }

    private fun toggleTemplateCards() {
        toggleCard(llmTemplateEnabled, llmTemplateCardPanel, llmTemplatePanel)
        toggleCard(loginEnabled, loginCardPanel, loginPanel)
    }

    private fun toggleCard(toggle: JCheckBox, wrapper: JPanel, content: JPanel) {
        val cardPanel = wrapper.components.filterIsInstance<JPanel>().firstOrNull() ?: return
        val layout = cardPanel.layout as CardLayout
        if (toggle.isSelected) {
            layout.show(cardPanel, "on")
        } else {
            layout.show(cardPanel, "off")
        }
        content.isEnabled = toggle.isSelected
        setEnabledDeep(content, toggle.isSelected)
    }

    private fun setEnabledDeep(component: JComponent, enabled: Boolean) {
        component.isEnabled = enabled
        component.components.filterIsInstance<JComponent>().forEach { setEnabledDeep(it, enabled) }
    }

    private fun infoBanner(text: String): JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEtchedBorder(),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        )
        add(JLabel(text), BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + 8)
    }

    private fun collectState(): AiTestSettingsModel = AiTestSettingsModel(
        llmProvider = providerField.selectedItem?.toString().orEmpty(),
        llmModel = modelField.text.trim(),
        llmEndpoint = endpointField.text.trim(),
        llmTimeoutSeconds = timeoutField.text.trim(),
        llmApiKey = String(apiKeyField.password).trim(),
        llmApiKeyEnv = apiKeyEnvField.text.trim(),
        httpDisableTlsVerification = httpDisableTlsVerification.isSelected,
        llmTemplateEnabled = llmTemplateEnabled.isSelected,
        llmTemplateMethod = llmTemplateMethod.selectedItem?.toString().orEmpty(),
        llmTemplateUrl = llmTemplateUrl.text.trim(),
        llmTemplateHeaders = llmTemplateHeaders.text.trim(),
        llmTemplateBody = llmTemplateBody.text,
        llmTemplateResponsePath = llmTemplateResponsePath.text.trim(),
        loginEnabled = loginEnabled.isSelected,
        loginMethod = loginMethod.selectedItem?.toString().orEmpty(),
        loginUrl = loginUrl.text.trim(),
        loginHeaders = loginHeaders.text.trim(),
        loginBody = loginBody.text,
        loginResponsePath = loginResponsePath.text.trim(),
        defaultFramework = defaultFrameworkField.selectedItem as? Framework ?: AiTestDefaults.DEFAULT_FRAMEWORK,
        defaultClassName = defaultClassNameField.text.trim(),
        defaultBaseUrl = defaultBaseUrlField.text.trim(),
        defaultNotes = defaultNotesField.text,
        commonLocation = commonLocationField.text.trim(),
        restAssuredLocation = restAssuredLocationField.text.trim(),
        restAssuredPackageName = restAssuredPackageNameField.text.trim(),
        karateLocation = karateLocationField.text.trim(),
        generationPromptWrapper = generationPromptWrapperField.text,
        generationPromptRestAssured = generationPromptRestAssuredField.text,
        generationPromptKarate = generationPromptKarateField.text,
        commitPrompt = commitPromptField.text,
        pullRequestPrompt = pullRequestPromptField.text,
        branchDiffPrompt = branchDiffPromptField.text,
        generationPromptProfileDefault = generationPromptProfileDefaultField.text.trim(),
        generationPromptProfilesYaml = generationPromptProfilesYamlField.text,
        commitPromptProfileDefault = commitPromptProfileDefaultField.text.trim(),
        commitPromptProfilesYaml = commitPromptProfilesYamlField.text,
        branchDiffPromptProfileDefault = branchDiffPromptProfileDefaultField.text.trim(),
        branchDiffPromptProfilesYaml = branchDiffPromptProfilesYamlField.text,
        codeGeneratePromptProfileDefault = codeGeneratePromptProfileDefaultField.text.trim(),
        codeGeneratePromptProfilesYaml = codeGeneratePromptProfilesYamlField.text,
        codeReviewPromptProfileDefault = codeReviewPromptProfileDefaultField.text.trim(),
        codeReviewPromptProfilesYaml = codeReviewPromptProfilesYamlField.text,
        bitbucketPromptRepoEnabled = bitbucketPromptRepoEnabledField.isSelected,
        bitbucketPromptRepoUrl = bitbucketPromptRepoUrlField.text.trim(),
        bitbucketPromptRepoBranch = bitbucketPromptRepoBranchField.text.trim(),
        bitbucketPromptRepoToken = String(bitbucketPromptRepoTokenField.password).trim()
    )

    private fun applyState(state: AiTestSettingsModel) {
        providerField.selectedItem = state.llmProvider
        modelField.text = state.llmModel
        endpointField.text = state.llmEndpoint
        timeoutField.text = state.llmTimeoutSeconds
        apiKeyField.setText(state.llmApiKey)
        apiKeyEnvField.text = state.llmApiKeyEnv
        httpDisableTlsVerification.isSelected = state.httpDisableTlsVerification

        llmTemplateEnabled.isSelected = state.llmTemplateEnabled
        llmTemplateMethod.selectedItem = state.llmTemplateMethod
        llmTemplateUrl.text = state.llmTemplateUrl
        llmTemplateHeaders.text = state.llmTemplateHeaders
        llmTemplateBody.text = state.llmTemplateBody
        llmTemplateResponsePath.text = state.llmTemplateResponsePath

        loginEnabled.isSelected = state.loginEnabled
        loginMethod.selectedItem = state.loginMethod
        loginUrl.text = state.loginUrl
        loginHeaders.text = state.loginHeaders
        loginBody.text = state.loginBody
        loginResponsePath.text = state.loginResponsePath

        defaultFrameworkField.selectedItem = state.defaultFramework
        defaultClassNameField.text = state.defaultClassName
        defaultBaseUrlField.text = state.defaultBaseUrl
        defaultNotesField.text = state.defaultNotes
        commonLocationField.text = state.commonLocation
        restAssuredLocationField.text = state.restAssuredLocation
        restAssuredPackageNameField.text = state.restAssuredPackageName
        karateLocationField.text = state.karateLocation
        generationPromptWrapperField.text = state.generationPromptWrapper
        generationPromptRestAssuredField.text = state.generationPromptRestAssured
        generationPromptKarateField.text = state.generationPromptKarate
        commitPromptField.text = state.commitPrompt
        branchDiffPromptField.text = state.branchDiffPrompt
        pullRequestPromptField.text = state.pullRequestPrompt
        generationPromptProfileDefaultField.text = state.generationPromptProfileDefault
        generationPromptProfilesYamlField.text = state.generationPromptProfilesYaml
        commitPromptProfileDefaultField.text = state.commitPromptProfileDefault
        commitPromptProfilesYamlField.text = state.commitPromptProfilesYaml
        branchDiffPromptProfileDefaultField.text = state.branchDiffPromptProfileDefault
        branchDiffPromptProfilesYamlField.text = state.branchDiffPromptProfilesYaml
        codeGeneratePromptProfileDefaultField.text = state.codeGeneratePromptProfileDefault
        codeGeneratePromptProfilesYamlField.text = state.codeGeneratePromptProfilesYaml
        codeReviewPromptProfileDefaultField.text = state.codeReviewPromptProfileDefault
        codeReviewPromptProfilesYamlField.text = state.codeReviewPromptProfilesYaml
        bitbucketPromptRepoEnabledField.isSelected = state.bitbucketPromptRepoEnabled
        bitbucketPromptRepoUrlField.text = state.bitbucketPromptRepoUrl
        bitbucketPromptRepoBranchField.text = state.bitbucketPromptRepoBranch
        bitbucketPromptRepoTokenField.text = state.bitbucketPromptRepoToken

        refreshPromptManager()
        toggleTemplateCards()
        updatePathLabel()
    }

    private fun validate(state: AiTestSettingsModel) {
        if (state.llmModel.isBlank()) {
            throw IllegalArgumentException("LLM model is required")
        }
        if (state.llmTimeoutSeconds.isBlank()) {
            throw IllegalArgumentException("Timeout is required")
        }
        if (state.llmTemplateEnabled) {
            requireTemplate("LLM template", state.llmTemplateUrl, state.llmTemplateBody, state.llmTemplateResponsePath)
        }
        if (state.loginEnabled) {
            requireTemplate("Login template", state.loginUrl, state.loginBody, state.loginResponsePath)
        }
        requirePromptProfiles("Test prompts YAML", state.generationPromptProfilesYaml)
        requirePromptProfiles("Commit prompts YAML", state.commitPromptProfilesYaml)
        requirePromptProfiles("Branch diff prompts YAML", state.branchDiffPromptProfilesYaml)
        requirePromptProfiles("Code generate prompts YAML", state.codeGeneratePromptProfilesYaml)
        requirePromptProfiles("Code review prompts YAML", state.codeReviewPromptProfilesYaml)
        if (state.bitbucketPromptRepoEnabled && state.bitbucketPromptRepoUrl.isBlank()) {
            throw IllegalArgumentException("Bitbucket prompt repo URL is required when enabled")
        }
    }

    private fun requireTemplate(label: String, url: String, body: String, responsePath: String) {
        if (url.isBlank() || body.isBlank() || responsePath.isBlank()) {
            throw IllegalArgumentException("$label requires URL, body, and response JSONPath")
        }
    }

    private fun requirePromptProfiles(label: String, text: String) {
        val parsed = Yaml().load<Any?>(text) as? Map<*, *>
            ?: throw IllegalArgumentException("$label must be a YAML map of profileName: promptTemplate")
        if (parsed.isEmpty()) {
            throw IllegalArgumentException("$label cannot be empty")
        }
    }

    private fun parseYamlMap(text: String): Map<String, String> {
        val parsed = Yaml().load<Any?>(text) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val result = linkedMapOf<String, String>()
        parsed.forEach { (k, v) ->
            val key = k?.toString()?.trim().orEmpty()
            val value = v?.toString().orEmpty().trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }

    private fun dumpYamlMap(value: Map<String, String>): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            isPrettyFlow = true
        }
        return Yaml(options).dump(value).trimEnd()
    }

    private fun isGlobalPromptName(name: String): Boolean {
        return name.startsWith("[ADA]") || name.startsWith("[repo]") || name.startsWith("global/")
    }

    private fun updatePathLabel() {
        if (project.basePath == null) return
        pathLabel?.text = "Project config: ${LlmSettingsLoader.configFilePath(project)}"
    }

    private fun saveCurrentState(): Boolean {
        return try {
            apply()
            true
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "AI Test Generator")
            false
        }
    }

    private fun methodCombo(): JComboBox<String> = JComboBox(arrayOf("POST", "GET", "PUT", "PATCH", "DELETE"))

    private fun textArea(rows: Int): JTextArea = JTextArea(rows, 80).apply {
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }
}
