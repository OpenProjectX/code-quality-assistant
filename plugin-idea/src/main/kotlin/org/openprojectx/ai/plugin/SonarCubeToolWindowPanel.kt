package org.openprojectx.ai.plugin

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.table.DefaultTableModel

object SonarCubeToolWindowPanel {
    private val allTypes = listOf("BUG", "VULNERABILITY", "CODE_SMELL")
    private val allSeverities = listOf("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")
    private val json = Json { ignoreUnknownKeys = true }

    private enum class ScanMode { ONLINE, LOCAL }

    fun create(project: Project, bgColor: Color, fgColor: Color, borderColor: Color, commonFont: Font): JPanel {
        val allIssues = mutableListOf<SonarCubeIssue>()
        val fixedKeys = mutableSetOf<String>()
        val issueListModel = DefaultListModel<SonarCubeIssue>()
        var isMockMode = false
        var scanMode = ScanMode.ONLINE
        var selectedIssue: SonarCubeIssue? = null
        var currentFileCoverages = emptyList<SonarQubeFileCoverage>()
        var dashboardPanel = JPanel(CardLayout()).apply { isOpaque = false }
        var currentDashboardMode = "metrics"

        val typeBoxes = allTypes.associateWith { JCheckBox(it, true) }
        val severityBoxes = allSeverities.associateWith { JCheckBox(it, true) }
        val showFixedBox = JCheckBox("Fixed", true)
        val filterCount = JLabel("").apply {
            foreground = fgColor
            font = commonFont.deriveFont(Font.PLAIN)
        }

        fun applyFilters() {
            val enabledTypes = typeBoxes.filter { it.value.isSelected }.keys.map { it.uppercase() }.toSet()
            val enabledSeverities = severityBoxes.filter { it.value.isSelected }.keys.map { it.uppercase() }.toSet()
            val filtered = allIssues.filter {
                it.type.uppercase() in enabledTypes &&
                    it.severity.uppercase() in enabledSeverities &&
                    (showFixedBox.isSelected || it.key !in fixedKeys)
            }
            val activeSize = allIssues.count { it.key !in fixedKeys }
            issueListModel.clear()
            filtered.forEach(issueListModel::addElement)
            val fixedText = if (fixedKeys.isNotEmpty()) "  ${fixedKeys.size} fixed" else ""
            filterCount.text = "($activeSize active)$fixedText"
        }

        fun runAllTestsWithCoverage() {
            try {
                val coverageExecutor = ExecutorRegistry.getInstance().getExecutorById("Coverage")
                if (coverageExecutor == null) {
                    Notifications.warn(project, "Coverage", "Coverage executor is not available.")
                    return
                }
                val runManager = RunManager.getInstance(project)
                var testSettings = runManager.allSettings.firstOrNull {
                    it.type.id == "JUnit" || it.type.id == "TestNG"
                }

                if (testSettings == null) {
                    val junitType = ConfigurationTypeUtil.findConfigurationType("JUnit")
                    if (junitType != null) {
                        val factory = junitType.configurationFactories.first()
                        val settings = runManager.createConfiguration("All Tests (CQA)", factory)
                        try {
                            val config = settings.configuration
                            val testKindField = config.javaClass.getField("ALL_IN_PACKAGE")
                            val testKindEnum = testKindField.get(null)
                            config.javaClass.getMethod("setTestKind", testKindEnum.javaClass)
                                .invoke(config, testKindEnum)
                            config.javaClass.getMethod("setPackageName", String::class.java)
                                .invoke(config, "")
                        } catch (_: Exception) { }
                        testSettings = settings
                        runManager.addConfiguration(settings)
                    }
                }

                if (testSettings == null) {
                    Notifications.info(project, "Coverage", "No test run configuration could be created.\nPlease create one via Run → Edit Configurations, then try again.")
                    return
                }
                runManager.selectedConfiguration = testSettings
                ProgramRunnerUtil.executeConfiguration(project, testSettings, coverageExecutor)
                Notifications.info(project, "Coverage", "Running tests with coverage. Click Refresh when done to see results.")
            } catch (ex: Exception) {
                Notifications.error(project, "Coverage", "Failed to run coverage: ${ex.message}")
            }
        }

        fun showFileCoverageList() {
            currentDashboardMode = "coverage"
            dashboardPanel.removeAll()

            val backButton = JButton("← Back to Dashboard").apply {
                foreground = Color(0x3B, 0x82, 0xF6)
                font = commonFont.deriveFont(Font.PLAIN, 12f)
                isOpaque = false
                isContentAreaFilled = false
                border = BorderFactory.createEmptyBorder(4, 0, 8, 0)
                addActionListener {
                    (dashboardPanel.layout as CardLayout).show(dashboardPanel, "metrics")
                    currentDashboardMode = "metrics"
                }
            }

            val runCoverageButton = JButton("▶ Run with Coverage").apply {
                foreground = Color(0x22, 0xC5, 0x5E)
                font = commonFont.deriveFont(Font.BOLD, 12f)
                isOpaque = false
                isContentAreaFilled = false
                border = BorderFactory.createEmptyBorder(4, 8, 8, 0)
                toolTipText = "Run all tests with coverage, then Refresh to see results"
                addActionListener {
                    runAllTestsWithCoverage()
                }
            }

            val emptyMessage = when {
                currentFileCoverages.isEmpty() && scanMode == ScanMode.LOCAL -> "No coverage data available.\nRun tests with Run → Run with Coverage in IntelliJ, then Refresh."
                currentFileCoverages.isEmpty() -> "No file coverage data returned from SonarQube.\nMake sure the project has been analyzed (sonar-scanner) and try Refresh."
                else -> null
            }

            if (emptyMessage != null) {
                val messageLabel = JLabel("<html>${emptyMessage.replace("\n", "<br>")}</html>").apply {
                    foreground = Color(0x94, 0xA3, 0xB8)
                    font = commonFont.deriveFont(Font.PLAIN, 13f)
                    horizontalAlignment = SwingConstants.CENTER
                    border = BorderFactory.createEmptyBorder(40, 20, 40, 20)
                }
                val topBar = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(backButton, BorderLayout.WEST)
                    add(runCoverageButton, BorderLayout.EAST)
                }
                dashboardPanel.add(JPanel(BorderLayout(0, 6)).apply {
                    isOpaque = false
                    add(topBar, BorderLayout.NORTH)
                    add(messageLabel, BorderLayout.CENTER)
                }, "coverage")
                (dashboardPanel.layout as CardLayout).show(dashboardPanel, "coverage")
                return
            }

            val summaryLabel = JLabel("${currentFileCoverages.size} files, sorted by coverage (lowest first)").apply {
                foreground = Color(0x94, 0xA3, 0xB8)
                font = commonFont.deriveFont(Font.PLAIN, 12f)
                border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            }

            val colNames = arrayOf("File", "Coverage", "Uncovered Lines")
            val dataArray = Array(currentFileCoverages.size) { i ->
                val fc = currentFileCoverages[i]
                arrayOf<Any>(fc.path, fc.coverage?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—", fc.uncoveredLines ?: 0)
            }

            fun generateTestsForCoverageFile(fileIndex: Int) {
                val fcc = currentFileCoverages.getOrNull(fileIndex) ?: return
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generate Tests: ${fcc.name}", false) {
                    override fun run(indicator: ProgressIndicator) {
                        try {
                            indicator.text = "Reading source file..."
                            val basePath = project.basePath
                            if (basePath.isNullOrBlank()) {
                                Notifications.warn(project, "Coverage", "Cannot resolve project base path")
                                return
                            }
                            val sourceFile = java.io.File(basePath, fcc.path)
                            if (!sourceFile.exists() || !sourceFile.isFile) {
                                Notifications.warn(project, "Coverage", "Source file not found: ${fcc.path}")
                                return
                            }
                            val sourceCode = sourceFile.readText(Charsets.UTF_8)

                            indicator.text = "Building test generation prompt..."
                            val cfg = LlmSettingsLoader.loadConfig(project)
                            val generationTemplate = cfg.prompts.generation.copy(
                                wrapper = PromptProfileResolver.resolve(
                                    cfg.prompts.profiles.generation,
                                    cfg.prompts.generation.wrapper
                                )
                            )
                            val prompt = org.openprojectx.ai.plugin.core.PromptBuilder.buildTestForUncoveredFile(
                                filePath = fcc.path,
                                fileName = fcc.name,
                                sourceCode = sourceCode,
                                uncoveredLines = fcc.uncoveredLines ?: 0,
                                coverage = fcc.coverage,
                                generationTemplate = generationTemplate
                            )

                            indicator.text = "Calling AI to generate tests..."
                            val response = LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
                                val provider = LlmProviderFactory.create(settings)
                                runBlocking { provider.generateCode(prompt) }
                            }

                            ApplicationManager.getApplication().invokeLater {
                                handleCoverageTestGeneration(project, fcc, sourceCode, response) { newCoverages ->
                                    currentFileCoverages = newCoverages
                                    showFileCoverageList()
                                }
                            }
                        } catch (ex: Exception) {
                            ApplicationManager.getApplication().invokeLater {
                                Notifications.error(project, "Test Generation failed", ex.message ?: ex.toString())
                            }
                        }
                    }
                })
            }

            fun showCoverageFileMenu(e: MouseEvent, row: Int, targetTable: JTable) {
                val fcv = currentFileCoverages.getOrNull(row) ?: return
                val menu = JPopupMenu()
                val genItem = JMenuItem("Generate Missing Tests")
                genItem.addActionListener { generateTestsForCoverageFile(row) }
                menu.add(genItem)
                menu.show(targetTable, e.x, e.y)
            }

            val tableModel = object : DefaultTableModel(dataArray, colNames) {
                override fun isCellEditable(row: Int, column: Int) = false
            }

            val table = JTable(tableModel).apply {
                background = Color(0x11, 0x1C, 0x2F)
                foreground = fgColor
                setShowGrid(true)
                gridColor = Color(0x1E, 0x29, 0x3B)
                rowHeight = 32
                font = commonFont.deriveFont(Font.PLAIN, 12f)
                tableHeader.apply {
                    background = Color(0x0B, 0x12, 0x20)
                    foreground = Color(0x94, 0xA3, 0xB8)
                    font = commonFont.deriveFont(Font.BOLD, 11f)
                }
                setSelectionBackground(Color(0x3B, 0x82, 0xF6))
                setSelectionForeground(Color.WHITE)

                columnModel.getColumn(0).preferredWidth = 420
                columnModel.getColumn(1).preferredWidth = 80
                columnModel.getColumn(2).preferredWidth = 120

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        val row = rowAtPoint(e.point)
                        if (row >= 0 && row < currentFileCoverages.size) {
                            setRowSelectionInterval(row, row)
                            if (e.clickCount >= 2) {
                                generateTestsForCoverageFile(row)
                            }
                            if (e.isPopupTrigger) showCoverageFileMenu(e, row, this@apply)
                        }
                    }
                    override fun mousePressed(e: MouseEvent) {
                        if (e.isPopupTrigger) {
                            val row = rowAtPoint(e.point)
                            if (row >= 0 && row < currentFileCoverages.size) {
                                setRowSelectionInterval(row, row)
                                showCoverageFileMenu(e, row, this@apply)
                            }
                        }
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        if (e.isPopupTrigger) {
                            val row = rowAtPoint(e.point)
                            if (row >= 0 && row < currentFileCoverages.size) {
                                setRowSelectionInterval(row, row)
                                showCoverageFileMenu(e, row, this@apply)
                            }
                        }
                    }
                })
            }

            val scrollPane = com.intellij.ui.components.JBScrollPane(table).apply {
                preferredSize = Dimension(320, 340)
                viewport.background = Color(0x11, 0x1C, 0x2F)
                border = BorderFactory.createLineBorder(borderColor)
            }

            val topBar = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(backButton, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply {
                    isOpaque = false
                    add(runCoverageButton)
                    add(summaryLabel)
                }, BorderLayout.EAST)
            }

            dashboardPanel.add(JPanel(BorderLayout(0, 6)).apply {
                isOpaque = false
                add(topBar, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
            }, "coverage")

            (dashboardPanel.layout as CardLayout).show(dashboardPanel, "coverage")
        }

        fun showContextMenu(comp: Component, x: Int, y: Int) {
            val issue = selectedIssue ?: return
            val menu = JPopupMenu()

            val goToItem = JMenuItem("Go to Line")
            goToItem.addActionListener { openIssue(project, issue, isMockMode) }
            menu.add(goToItem)

            val aiFixItem = JMenuItem("AI Fix...")
            val fixingIssue = issue
            aiFixItem.addActionListener {
                executeAiFix(project, fixingIssue) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val basePath = project.basePath
                        if (basePath != null) {
                            val ioFile = java.io.File(basePath, fixingIssue.path)
                            val vf = VfsUtil.findFileByIoFile(ioFile, true)
                            if (vf != null) {
                                val newFileIssues = SonarCubeLocalScanner.scanFile(project, vf)
                                ApplicationManager.getApplication().invokeLater {
                                    allIssues.removeAll { it.path == fixingIssue.path }
                                    allIssues.addAll(newFileIssues)
                                    val isResolved = newFileIssues.none { it.rule == fixingIssue.rule }
                                    if (isResolved) {
                                        fixedKeys.add(fixingIssue.key)
                                        Notifications.info(project, "Sonar Fix", "Issue resolved: ${fixingIssue.rule}")
                                    } else {
                                        Notifications.warn(project, "Sonar Fix", "Issue may still exist after fix. Please verify manually.")
                                    }
                                    applyFilters()
                                }
                            }
                        }
                    }
                }
            }
            menu.add(aiFixItem)

            menu.addSeparator()

            if (issue.key in fixedKeys) {
                val unfixItem = JMenuItem("Unmark as fixed")
                unfixItem.addActionListener {
                    fixedKeys.remove(issue.key)
                    applyFilters()
                }
                menu.add(unfixItem)
            } else {
                val fixItem = JMenuItem("Mark as fixed")
                fixItem.addActionListener {
                    fixedKeys.add(issue.key)
                    applyFilters()
                }
                menu.add(fixItem)
            }

            menu.show(comp, x, y)
        }

        val issueList = JList(issueListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            background = Color(0x11, 0x1C, 0x2F)
            foreground = fgColor
            fixedCellHeight = 58
            cellRenderer = SonarCubeIssueRenderer(fixedKeys)
            addListSelectionListener {
                if (!it.valueIsAdjusting) selectedIssue = selectedValue
            }
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) maybeShowMenu(e)
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) maybeShowMenu(e)
                }
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount >= 2) {
                        selectedValue?.let { openIssue(project, it, isMockMode) }
                    }
                }
                private fun maybeShowMenu(e: MouseEvent) {
                    val idx = locationToIndex(e.point)
                    if (idx >= 0) {
                        setSelectedIndex(idx)
                        selectedIssue = selectedValue
                        showContextMenu(this@apply, e.x, e.y)
                    }
                }
            })
        }
        val refreshButton = JButton("Refresh")
        val reportDate = JLabel("").apply {
            foreground = Color(0x94, 0xA3, 0xB8)
            font = commonFont.deriveFont(Font.PLAIN, 10f)
        }
        val loading = AtomicBoolean(false)

        typeBoxes.values.forEach { box ->
            box.foreground = fgColor
            box.isOpaque = false
            box.font = commonFont.deriveFont(Font.PLAIN, 11f)
            box.addActionListener { applyFilters() }
        }
        severityBoxes.values.forEach { box ->
            box.foreground = fgColor
            box.isOpaque = false
            box.font = commonFont.deriveFont(Font.PLAIN, 11f)
            box.addActionListener { applyFilters() }
        }
        showFixedBox.foreground = fgColor
        showFixedBox.isOpaque = false
        showFixedBox.font = commonFont.deriveFont(Font.PLAIN, 11f)
        showFixedBox.addActionListener { applyFilters() }

        fun filterByType(type: String) {
            typeBoxes.forEach { (key, box) -> box.isSelected = key.uppercase() == type.uppercase() }
            applyFilters()
        }

        fun resetTypeFilters() {
            typeBoxes.values.forEach { it.isSelected = true }
            applyFilters()
        }

        fun finishLoading() {
            loading.set(false)
            refreshButton.isEnabled = true
        }

        fun load() {
            if (!loading.compareAndSet(false, true)) return
            refreshButton.isEnabled = false
            allIssues.clear()
            fixedKeys.clear()
            issueListModel.clear()
            refreshDashboard(dashboardPanel, bgColor, borderColor, commonFont, "Loading...")
            reportDate.text = ""
            if (scanMode == ScanMode.LOCAL) {
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Sonar Cube Local Scan", false) {
                    override fun run(indicator: ProgressIndicator) {
                        try {
                            val result = SonarCubeLocalScanner.scan(project, indicator)
                            ApplicationManager.getApplication().invokeLater {
                                currentFileCoverages = result.fileCoverages
                                refreshDashboard(dashboardPanel, bgColor, borderColor, commonFont, result,
                                    { filterByType(it) }, { resetTypeFilters() }) { showFileCoverageList() }
                                reportDate.text = result.reportTimestamp ?: ""
                                allIssues.clear()
                                allIssues.addAll(result.issues)
                                typeBoxes.values.forEach { it.isSelected = true }
                                severityBoxes.values.forEach { it.isSelected = true }
                                applyFilters()
                                finishLoading()
                            }
                        } catch (ex: Exception) {
                            ApplicationManager.getApplication().invokeLater {
                                refreshDashboard(dashboardPanel, bgColor, borderColor, commonFont,
                                    "Local scan failed: ${ex.message ?: ex}", null, null)
                                reportDate.text = ""
                                finishLoading()
                                Notifications.error(project, "Local Scan failed", ex.message ?: ex.toString())
                            }
                        }
                    }
                })
            } else {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val config = SonarQubeProjectSettings.getInstance(project).resolveConfig()
                        isMockMode = config.mockEnabled
                        if (config.serverUrl.isBlank() || config.projectKey.isBlank()) {
                            ApplicationManager.getApplication().invokeLater {
                                refreshDashboard(dashboardPanel, bgColor, borderColor, commonFont, "SonarQube not configured.")
                                reportDate.text = ""
                                finishLoading()
                            }
                            return@executeOnPooledThread
                        }

                        val result = runBlocking { SonarCubeToolWindowClient(config).load() }
                        ApplicationManager.getApplication().invokeLater {
                            currentFileCoverages = result.fileCoverages
                            refreshDashboard(dashboardPanel, bgColor, borderColor, commonFont, result,
                                { filterByType(it) }, { resetTypeFilters() }) { showFileCoverageList() }
                            reportDate.text = result.reportTimestamp ?: ""
                            allIssues.clear()
                            allIssues.addAll(result.issues)
                            typeBoxes.values.forEach { it.isSelected = true }
                            severityBoxes.values.forEach { it.isSelected = true }
                            applyFilters()
                            finishLoading()
                        }
                    } catch (ex: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            refreshDashboard(dashboardPanel, bgColor, borderColor, commonFont, "Failed: ${ex.message ?: ex}", null, null)
                            reportDate.text = ""
                            finishLoading()
                            Notifications.error(project, "SonarQube Results failed", ex.message ?: ex.toString())
                        }
                    }
                }
            }
        }

        refreshButton.addActionListener { load() }

        val typeFilterRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JLabel("Type:").apply {
                foreground = Color(0x94, 0xA3, 0xB8)
                font = commonFont.deriveFont(Font.PLAIN, 10f)
            })
            typeBoxes.values.forEach { add(it) }
        }
        val severityFilterRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JLabel("Severity:").apply {
                foreground = Color(0x94, 0xA3, 0xB8)
                font = commonFont.deriveFont(Font.PLAIN, 10f)
            })
            severityBoxes.values.forEach { add(it) }
            add(Box.createHorizontalStrut(12))
            add(showFixedBox)
        }
        val filterBar = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
            )
            add(typeFilterRow, BorderLayout.NORTH)
            add(severityFilterRow, BorderLayout.CENTER)
        }
        val issueScrollPane = com.intellij.ui.components.JBScrollPane(issueList).apply {
            preferredSize = Dimension(320, 340)
            viewport.background = Color(0x11, 0x1C, 0x2F)
            border = BorderFactory.createLineBorder(borderColor)
        }
        val dashboardAndFilter = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(dashboardPanel)
            add(filterBar)
        }
        val filterPanel = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            add(dashboardAndFilter, BorderLayout.NORTH)
            add(issueScrollPane, BorderLayout.CENTER)
        }

        val root = JPanel(BorderLayout(8, 8)).apply {
            background = bgColor
            foreground = fgColor
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val projectKeyField = JTextField(28).apply {
                val initialKey = SonarQubeProjectSettings.getInstance(project).resolveConfig().projectKey
                text = initialKey
                foreground = fgColor
                background = Color(0x11, 0x1C, 0x2F)
                font = commonFont.deriveFont(Font.PLAIN, 11f)
                toolTipText = "SonarQube project key"
                addActionListener { // Enter key
                    SonarQubeProjectSettings.getInstance(project).projectKey = text.trim()
                    if (scanMode == ScanMode.ONLINE) load()
                }
                addFocusListener(object : java.awt.event.FocusAdapter() {
                    override fun focusLost(e: java.awt.event.FocusEvent?) {
                        val saved = SonarQubeProjectSettings.getInstance(project).projectKey
                        if (text.trim() != saved) {
                            SonarQubeProjectSettings.getInstance(project).projectKey = text.trim()
                            if (scanMode == ScanMode.ONLINE) load()
                        }
                    }
                })
            }
            val modeCombo = JComboBox(arrayOf("Online", "Local")).apply {
                foreground = fgColor
                background = Color(0x11, 0x1C, 0x2F)
                font = commonFont.deriveFont(Font.PLAIN, 11f)
                toolTipText = "Online: fetch from SonarQube server / Local: run IntelliJ inspections"
                addActionListener {
                    scanMode = if (selectedItem == "Local") ScanMode.LOCAL else ScanMode.ONLINE
                    projectKeyField.isEnabled = scanMode == ScanMode.ONLINE
                    allIssues.clear()
                    fixedKeys.clear()
                    issueListModel.clear()
                    val msg = if (scanMode == ScanMode.LOCAL) {
                        "Local scan mode. Click Refresh to run IntelliJ inspections."
                    } else {
                        "Online mode. Click Refresh to load configured SonarQube results."
                    }
                    refreshDashboard(dashboardPanel, bgColor, borderColor, commonFont, msg, null, null, null)
                    reportDate.text = ""
                }
            }
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(JLabel("Sonar Cube").apply { foreground = fgColor })
                    add(modeCombo)
                }, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                    add(filterCount)
                    add(reportDate)
                    add(refreshButton)
                }, BorderLayout.EAST)
                add(JPanel(FlowLayout(FlowLayout.CENTER, 6, 0)).apply {
                    isOpaque = false
                    add(JLabel("Project Key:").apply {
                        foreground = Color(0x94, 0xA3, 0xB8)
                        font = commonFont.deriveFont(Font.PLAIN, 11f)
                    })
                    add(projectKeyField)
                }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(filterPanel, BorderLayout.CENTER)
        }
        refreshDashboard(dashboardPanel, bgColor, borderColor, commonFont, "Click Refresh to load configured SonarQube results.", null, null)
        load()
        return root
    }

    private fun executeAiFix(project: Project, issue: SonarCubeIssue, onFixApplied: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI Fix: ${issue.rule}", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Reading source file..."
                    val sourceCode = readSourceFile(project, issue.path)
                    if (sourceCode.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            Notifications.warn(project, "AI Fix", "Cannot read source file: ${issue.path}")
                        }
                        return
                    }

                    indicator.text = "Calling AI to generate fix..."
                    val prompt = AiPromptDefaults.render(
                        AiPromptDefaults.SONARQUBE_FIX_ISSUE,
                        mapOf(
                            "rule" to issue.rule,
                            "severity" to issue.severity,
                            "type" to issue.type,
                            "message" to issue.message,
                            "filePath" to issue.path,
                            "line" to (issue.line?.toString() ?: "unknown"),
                            "sourceCode" to sourceCode
                        )
                    )
                    val response = LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
                        val provider = LlmProviderFactory.create(settings)
                        runBlocking { provider.generateCode(prompt) }
                    }

                    ApplicationManager.getApplication().invokeLater {
                        handleFixResponse(project, issue, response, sourceCode, onFixApplied)
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.error(project, "AI Fix failed", ex.message ?: ex.toString())
                    }
                }
            }
        })
    }

    private fun handleFixResponse(project: Project, issue: SonarCubeIssue, response: String, originalCode: String, onFixApplied: (() -> Unit)? = null) {
        val explanation = extractExplanation(response)
        val codeBlock = extractCodeBlock(response)
        if (codeBlock != null) {
            val dialog = SonarQubeFixDialog(project, issue, explanation, originalCode, codeBlock)
            if (dialog.showAndGet()) {
                applyCodeChange(project, issue.path, codeBlock, onFixApplied)
            }
            return
        }

        val options = tryParseOptions(response)
        if (options.isNotEmpty()) {
            val labels = options.map { it.label }.toTypedArray()
            val choice = Messages.showDialog(
                project,
                "Multiple approaches are possible. Choose one:",
                "AI Fix — ${issue.rule}",
                labels + "Cancel",
                labels.size,
                null
            )
            if (choice >= 0 && choice < options.size) {
                applyCodeChange(project, issue.path, options[choice].code, onFixApplied)
            }
            return
        }

        Messages.showInfoMessage(project, response, "AI Fix — ${issue.rule}")
    }

    private fun applyCodeChange(project: Project, relativePath: String, newCode: String, onFixApplied: (() -> Unit)? = null) {
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            Notifications.warn(project, "AI Fix", "Cannot determine project base path.")
            return
        }
        val file = java.io.File(basePath, relativePath)
        val virtualFile = VfsUtil.findFileByIoFile(file, true)
        if (virtualFile == null) {
            Notifications.warn(project, "AI Fix", "Cannot find file to apply fix: $relativePath")
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            virtualFile.setBinaryContent(newCode.toByteArray(StandardCharsets.UTF_8))
        }
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        if (onFixApplied != null) {
            onFixApplied()
        } else {
            Notifications.info(project, "AI Fix", "Fix applied to $relativePath. Check IDE local history to revert if needed.")
        }
    }

    private fun handleCoverageTestGeneration(
        project: Project,
        fileCoverage: SonarQubeFileCoverage,
        sourceCode: String,
        response: String,
        onCoverageUpdated: (List<SonarQubeFileCoverage>) -> Unit
    ) {
        val sanitized = sanitizeGeneratedCode(response)
        val codeBlock = extractCodeBlock(response)
        val displayCode = codeBlock ?: sanitized

        val sourceFileName = fileCoverage.name.removeSuffix(".java").removeSuffix(".kt")
        val testClassName = "${sourceFileName}Test"
        val packageHint = sourceCode.lines()
            .firstOrNull { it.trimStart().startsWith("package ") }
            ?.trim()?.removeSuffix(";")?.removePrefix("package ")
        val testDir = if (fileCoverage.path.contains("/main/")) {
            fileCoverage.path.replace("/main/", "/test/")
                .substringBeforeLast("/")
        } else {
            "src/test/java/${packageHint?.replace('.', '/') ?: ""}"
        }.trimEnd('/')

        val dialog = CoverageTestGenerationDialog(
            project, fileCoverage, testClassName, testDir, displayCode, sourceCode
        )
        if (dialog.showAndGet()) {
            val outputRelativePath = "${dialog.outputPath()}/${dialog.testClassName()}.java"
                .replace('\\', '/')
                .trimStart('/')
            val basePath = project.basePath
            if (basePath.isNullOrBlank()) {
                Notifications.warn(project, "Test Generation", "Cannot determine project base path.")
                return
            }
            val outputFile = java.io.File(basePath, outputRelativePath)
            outputFile.parentFile?.mkdirs()
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    val vf = VfsUtil.findFileByIoFile(outputFile, true)
                        ?: VfsUtil.findFileByIoFile(outputFile.parentFile!!, true)
                            ?.createChildData(this, outputFile.name)
                    if (vf != null) {
                        vf.setBinaryContent(dialog.testCode().toByteArray(StandardCharsets.UTF_8))
                    }
                }
                FileEditorManager.getInstance(project).openFile(
                    VfsUtil.findFileByIoFile(outputFile, true)!!, true
                )
                Notifications.info(project, "Test Generation", "Test file created: $outputRelativePath")

                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val config = SonarQubeProjectSettings.getInstance(project).resolveConfig()
                        if (config.serverUrl.isNotBlank() && config.projectKey.isNotBlank()) {
                            val updated = runBlocking { SonarCubeToolWindowClient(config).load() }
                            ApplicationManager.getApplication().invokeLater {
                                onCoverageUpdated(updated.fileCoverages)
                                Notifications.info(project, "Coverage Report", "Coverage data refreshed.")
                            }
                        }
                    } catch (_: Exception) { }
                }
            } catch (ex: Exception) {
                Notifications.error(project, "Test Generation", "Failed to write test file: ${ex.message}")
            }
        }
    }

    private fun extractExplanation(response: String): String {
        val fenceIdx = response.indexOf("```")
        return if (fenceIdx > 0) response.substring(0, fenceIdx).trim() else ""
    }

    private fun readSourceFile(project: Project, relativePath: String): String? {
        val basePath = project.basePath ?: return null
        val file = java.io.File(basePath, relativePath)
        if (!file.exists() || !file.isFile) return null
        return file.readText(Charsets.UTF_8)
    }

    private fun sanitizeGeneratedCode(raw: String): String {
        val trimmed = raw.trim()
        val withoutStartFence = trimmed.replaceFirst(Regex("^```(?:\\w+)?\\s*\\n?"), "")
        return withoutStartFence.replaceFirst(Regex("\\n?```\\s*$"), "").trim()
    }

    private fun extractCodeBlock(response: String): String? {
        val fence = "```"
        val start = response.indexOf(fence)
        if (start < 0) return null
        val contentStart = response.indexOf('\n', start)
        if (contentStart < 0) return null
        val end = response.indexOf(fence, contentStart + 1)
        if (end < 0) return null
        return response.substring(contentStart + 1, end).trimEnd()
    }

    private fun tryParseOptions(response: String): List<FixOption> {
        return try {
            val trimmed = response.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val arr = json.parseToJsonElement(trimmed).jsonArray
            arr.map { el ->
                val obj = el.jsonObject
                FixOption(
                    label = obj["label"]?.jsonPrimitive?.content ?: "Option",
                    code = obj["code"]?.jsonPrimitive?.content ?: ""
                )
            }.filter { it.code.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun refreshDashboard(
        container: JPanel,
        bgColor: Color,
        borderColor: Color,
        font: Font,
        result: Any,
        onFilterByType: ((String) -> Unit)? = null,
        onResetFilters: (() -> Unit)? = null,
        onCoverageClick: (() -> Unit)? = null
    ) {
        container.removeAll()

        if (result is String) {
            val label = JLabel(result).apply {
                foreground = Color(0x94, 0xA3, 0xB8)
                isOpaque = false
                horizontalAlignment = SwingConstants.CENTER
            }
            val panel = JPanel(BorderLayout(0, 0)).apply {
                isOpaque = false
                add(label, BorderLayout.CENTER)
            }
            container.add(panel, "metrics")
            (container.layout as CardLayout).show(container, "metrics")
            container.revalidate()
            container.repaint()
            return
        }

        val r = result as SonarCubeResult
        val cardFont = font.deriveFont(Font.PLAIN, 10f)
        val valueFont = font.deriveFont(Font.BOLD, 14f)

        val cards = JPanel(GridLayout(2, 4, 4, 4)).apply {
            isOpaque = false
        }

        cards.add(metricCard("Coverage", r.coverage?.formatPercent() ?: "—", "#42A5F5", r.coverage != null, valueFont, cardFont, borderColor, onCoverageClick))
        cards.add(metricCard("Line Cov.", r.lineCoverage?.formatPercent() ?: "—", "#26C6DA", r.lineCoverage != null, valueFont, cardFont, borderColor))
        cards.add(metricCard("Branch Cov.", r.branchCoverage?.formatPercent() ?: "—", "#009688", r.branchCoverage != null, valueFont, cardFont, borderColor))
        cards.add(metricCard("Uncovered", r.uncoveredLines?.toString() ?: "—", "#EF5350", r.uncoveredLines != null, valueFont, cardFont, borderColor))
        cards.add(metricCard("Bugs", r.bugs?.toString() ?: "—", "#F44336", r.bugs != null && r.bugs > 0, valueFont, cardFont, borderColor) { onFilterByType?.invoke("BUG") })
        cards.add(metricCard("Vulnerabilities", r.vulnerabilities?.toString() ?: "—", "#FF9800", r.vulnerabilities != null && r.vulnerabilities > 0, valueFont, cardFont, borderColor) { onFilterByType?.invoke("VULNERABILITY") })
        cards.add(metricCard("Code Smells", r.codeSmells?.toString() ?: "—", "#FFC107", r.codeSmells != null && r.codeSmells > 0, valueFont, cardFont, borderColor) { onFilterByType?.invoke("CODE_SMELL") })
        cards.add(metricCard("Open Issues", r.issues.size.toString(), "#E0E0E0", r.issues.isNotEmpty(), valueFont, cardFont, borderColor) { onResetFilters?.invoke() })

        container.add(cards, "metrics")
        (container.layout as CardLayout).show(container, "metrics")
        container.revalidate()
        container.repaint()
    }

    private fun metricCard(
        label: String,
        value: String,
        accentColor: String,
        highlight: Boolean,
        valueFont: Font,
        labelFont: Font,
        borderColor: Color,
        onClick: (() -> Unit)? = null
    ): JComponent {
        return JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(if (highlight) Color.decode(accentColor).darker() else borderColor),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
            if (onClick != null) {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onClick()
                    override fun mouseEntered(e: MouseEvent) { background = Color(0x22, 0x28, 0x33); isOpaque = true }
                    override fun mouseExited(e: MouseEvent) { isOpaque = false }
                })
            }

            val valueColor = if (highlight) Color.decode(accentColor) else Color(0x94, 0xA3, 0xB8)
            add(JLabel(value).apply {
                foreground = valueColor
                font = valueFont
                horizontalAlignment = SwingConstants.CENTER
            }, BorderLayout.CENTER)
            add(JLabel(label).apply {
                foreground = Color(0x94, 0xA3, 0xB8)
                font = labelFont
                horizontalAlignment = SwingConstants.CENTER
            }, BorderLayout.SOUTH)
        }
    }

    private fun openIssue(project: Project, issue: SonarCubeIssue, mockEnabled: Boolean = false) {
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            Notifications.warn(project, "Sonar Cube", "Cannot open file because project base path is unknown.")
            return
        }
        val file = java.io.File(basePath, issue.path)
        val virtualFile = VfsUtil.findFileByIoFile(file, true)
        if (virtualFile == null) {
            if (mockEnabled) {
                Notifications.info(project, "Sonar Cube", "${issue.path}:${issue.line ?: 1} — this is mock scan data; no local file exists.")
            } else {
                Notifications.warn(project, "Sonar Cube", "Cannot find local file: ${issue.path}")
            }
            return
        }
        val lineIndex = issue.line?.minus(1)?.coerceAtLeast(0) ?: 0
        OpenFileDescriptor(project, virtualFile, lineIndex, 0).navigate(true)
    }

    private data class FixOption(val label: String, val code: String)

    private class SonarQubeFixDialog(
        project: Project,
        private val issue: SonarCubeIssue,
        private val explanation: String,
        private val originalCode: String,
        private val fixedCode: String
    ) : DialogWrapper(project) {

        private val severityColor = when (issue.severity.uppercase()) {
            "BLOCKER" -> Color(0xD3, 0x2F, 0x2F)
            "CRITICAL" -> Color(0xE6, 0x51, 0x00)
            "MAJOR" -> Color(0xF9, 0xA8, 0x25)
            "MINOR" -> Color(0x38, 0x8E, 0x3C)
            "INFO" -> Color(0x19, 0x76, 0xD2)
            else -> Color(0x94, 0xA3, 0xB8)
        }

        private val codeFont = Font("Monospaced", Font.PLAIN, 12)
        private val bgCode = Color(0x0D, 0x11, 0x17)
        private val fgCode = Color(0xCC, 0xCC, 0xCC)

        init {
            title = "AI Fix — ${issue.rule}"
            init()
            myOKAction.putValue(javax.swing.Action.NAME, "Apply Fix")
        }

        override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
            add(createIssueHeader(), BorderLayout.NORTH)
            add(createSideBySideDiff(), BorderLayout.CENTER)
            add(createExplanation(), BorderLayout.SOUTH)
            preferredSize = Dimension(800, 460)
        }

        private fun createIssueHeader(): JComponent = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
            isOpaque = false

            val severityLabel = JLabel(" ${issue.severity} ").apply {
                foreground = Color.WHITE
                background = severityColor
                isOpaque = true
                font = font.deriveFont(Font.BOLD, 11f)
                border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            }
            val typeLabel = JLabel("   ${issue.type}").apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = Color(0xE0, 0xE0, 0xE0)
            }
            val locationLabel = JLabel("${issue.path}:${issue.line}  —  ${issue.rule}").apply {
                foreground = Color(0x94, 0xA3, 0xB8)
                font = font.deriveFont(Font.PLAIN, 11f)
            }

            val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(severityLabel)
                add(typeLabel)
                add(locationLabel)
            }

            val messageLabel = JLabel("<html><b>${escapeHtml(issue.message)}</b></html>").apply {
                foreground = Color(0xE0, 0xE0, 0xE0)
                font = font.deriveFont(Font.PLAIN, 13f)
                border = BorderFactory.createEmptyBorder(4, 6, 0, 6)
            }

            add(topRow, BorderLayout.NORTH)
            add(messageLabel, BorderLayout.CENTER)
        }

        private fun createSideBySideDiff(): JComponent {
            val beforeText = extractChangedRegion(originalCode, fixedCode, side = "before")
            val afterText = extractChangedRegion(originalCode, fixedCode, side = "after")

            val beforeArea = JTextArea(beforeText).apply {
                isEditable = false
                foreground = fgCode
                background = bgCode
                font = codeFont
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }
            val afterArea = JTextArea(afterText).apply {
                isEditable = false
                foreground = fgCode
                background = bgCode
                font = codeFont
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }

            val beforeScroll = com.intellij.ui.components.JBScrollPane(beforeArea).apply {
                border = BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(Color(0x30, 0x40, 0x50)),
                    "Current code",
                    javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                    javax.swing.border.TitledBorder.DEFAULT_POSITION,
                    null,
                    Color(0x94, 0xA3, 0xB8)
                )
                preferredSize = Dimension(350, 220)
                viewport.background = bgCode
            }
            val afterScroll = com.intellij.ui.components.JBScrollPane(afterArea).apply {
                border = BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(Color(0x38, 0x8E, 0x3C)),
                    "Proposed fix",
                    javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                    javax.swing.border.TitledBorder.DEFAULT_POSITION,
                    null,
                    Color(0x94, 0xA3, 0xB8)
                )
                preferredSize = Dimension(350, 220)
                viewport.background = bgCode
            }

            val arrowLabel = JLabel("<html><div style='font-size:28pt;color:#F9A825;padding:0 12px'>&#10140;</div></html>").apply {
                isOpaque = false
            }

            val diffPanel = JPanel(GridBagLayout()).apply {
                isOpaque = false
                val gbc = GridBagConstraints()
                gbc.fill = GridBagConstraints.BOTH
                gbc.weightx = 1.0
                gbc.weighty = 1.0
                gbc.gridx = 0; gbc.gridy = 0
                add(beforeScroll, gbc)
                gbc.gridx = 1; gbc.weightx = 0.0
                add(arrowLabel, gbc)
                gbc.gridx = 2; gbc.weightx = 1.0
                add(afterScroll, gbc)
            }

            return diffPanel
        }

        private fun createExplanation(): JComponent {
            if (explanation.isBlank()) return JPanel()
            val area = JTextArea(explanation).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                foreground = Color(0xB0, 0xB0, 0xB0)
                background = Color(0x16, 0x1B, 0x22)
                font = font.deriveFont(Font.PLAIN, 12f)
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }
            return com.intellij.ui.components.JBScrollPane(area).apply {
                border = BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(Color(0x30, 0x40, 0x50)),
                    "Fix logic — how the issue is resolved",
                    javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                    javax.swing.border.TitledBorder.DEFAULT_POSITION,
                    null,
                    Color(0x94, 0xA3, 0xB8)
                )
                preferredSize = Dimension(780, 60)
                viewport.background = Color(0x16, 0x1B, 0x22)
            }
        }

        private fun extractChangedRegion(original: String, fixed: String, side: String): String {
            val origLines = original.split("\n")
            val fixedLines = fixed.split("\n")

            var firstDiff = 0
            while (firstDiff < origLines.size && firstDiff < fixedLines.size &&
                origLines[firstDiff] == fixedLines[firstDiff]
            ) firstDiff++

            var lastOrig = origLines.lastIndex
            var lastFixed = fixedLines.lastIndex
            while (lastOrig > firstDiff && lastFixed > firstDiff &&
                origLines[lastOrig] == fixedLines[lastFixed]
            ) {
                lastOrig--
                lastFixed--
            }

            val context = 3
            val showStart = (firstDiff - context).coerceAtLeast(0)
            val showEndO = (lastOrig + context + 1).coerceAtMost(origLines.size)
            val showEndF = (lastFixed + context + 1).coerceAtMost(fixedLines.size)

            return buildString {
                val lines = if (side == "before") origLines else fixedLines
                val end = if (side == "before") showEndO else showEndF
                for (i in showStart..<end) {
                    val line = if (i < lines.size) lines[i] else ""
                    val num = i + 1
                    val isTarget = if (side == "before") i == (issue.line ?: 1) - 1 else false
                    val prefix = if (isTarget && side == "before") "▶" else " "
                    appendLine("$prefix ${num.toString().padStart(4)}  $line")
                }
            }.trimEnd()
        }

        private fun escapeHtml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

private class CoverageTestGenerationDialog(
    project: Project,
    private val fileCoverage: SonarQubeFileCoverage,
    private val testClassName: String,
    private val testDir: String,
    private val generatedCode: String,
    private val originalSource: String
) : DialogWrapper(project) {

    private val outputDirField = JTextField(testDir, 50)
    private val classNameField = JTextField(testClassName, 30)
    private val codeArea = JTextArea(generatedCode, 20, 70).apply {
        isEditable = true
        lineWrap = false
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
        background = Color(0x0D, 0x11, 0x17)
        foreground = Color(0xCC, 0xCC, 0xCC)
        caretColor = Color(0xCC, 0xCC, 0xCC)
    }

    init {
        title = "Generate Tests — ${fileCoverage.name}"
        init()
        myOKAction.putValue(javax.swing.Action.NAME, "Create Test File")
    }

    override fun createCenterPanel(): JComponent = JPanel(java.awt.BorderLayout(0, 10)).apply {
        add(JPanel(java.awt.BorderLayout(8, 0)).apply {
            add(JLabel("<html><b>${fileCoverage.path}</b>&nbsp;&nbsp;|&nbsp;&nbsp;Coverage: ${fileCoverage.coverage?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—"}&nbsp;&nbsp;|&nbsp;&nbsp;Uncovered: ${fileCoverage.uncoveredLines ?: 0} lines</html>"), java.awt.BorderLayout.NORTH)
        }, java.awt.BorderLayout.NORTH)

        val configPanel = JPanel(java.awt.GridBagLayout()).apply {
            border = javax.swing.BorderFactory.createTitledBorder("Output Settings")
            val gbc = java.awt.GridBagConstraints().apply {
                fill = java.awt.GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(4, 4, 4, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
            add(JLabel("Output Directory:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(outputDirField, gbc)
            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
            add(JLabel("Test Class Name:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(classNameField, gbc)
        }

        val codePanel = JPanel(java.awt.BorderLayout()).apply {
            border = javax.swing.BorderFactory.createTitledBorder("Generated Test Code (edit if needed)")
            add(com.intellij.ui.components.JBScrollPane(codeArea).apply {
                preferredSize = java.awt.Dimension(700, 340)
                viewport.background = Color(0x0D, 0x11, 0x17)
            }, java.awt.BorderLayout.CENTER)
        }

        val centerPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(configPanel)
            add(javax.swing.Box.createVerticalStrut(8))
            add(codePanel)
        }
        add(centerPanel, java.awt.BorderLayout.CENTER)
        preferredSize = java.awt.Dimension(750, 520)
    }

    fun outputPath(): String = outputDirField.text.trim().replace('\\', '/').trimEnd('/')
    fun testClassName(): String = classNameField.text.trim().removeSuffix(".java")
    fun testCode(): String = codeArea.text
}

private class SonarCubeToolWindowClient(private val config: SonarQubeConfig) {
    private val authHeader = SonarQubeAuth.authorizationHeader(config)

    suspend fun load(): SonarCubeResult {
        if (config.mockEnabled) {
            return SonarQubeMockData.scanResult(config.projectKey, config.serverUrl)
        }
        val client = HttpClients.shared(disableTlsVerification = true, timeoutSeconds = 60)
        try {
            val baseUrl = config.serverUrl.trimEnd('/')
            val projectKey = encoded(config.projectKey)
            val measuresUrl = "$baseUrl/api/measures/component?component=$projectKey&metricKeys=coverage,line_coverage,branch_coverage,uncovered_lines,bugs,vulnerabilities,code_smells"
            HttpClients.logCurl("GET", measuresUrl, authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap())
            val measures: SonarCubeMeasuresResponse = client.get(measuresUrl) {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }.body()
            val fileMeasuresUrl = "$baseUrl/api/measures/component_tree?component=$projectKey&metricKeys=coverage,uncovered_lines&qualifiers=FIL&ps=500"
            HttpClients.logCurl("GET", fileMeasuresUrl, authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap())
            val fileTree = runCatching {
                val resp: SonarCubeComponentTreeResponse = client.get(fileMeasuresUrl) {
                    authHeader?.let { header(HttpHeaders.Authorization, it) }
                }.body()
                resp
            }.getOrDefault(SonarCubeComponentTreeResponse())
            val issuesUrl = "$baseUrl/api/issues/search?componentKeys=$projectKey&resolved=false&ps=100&s=SEVERITY&asc=false"
            HttpClients.logCurl("GET", issuesUrl, authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap())
            val issues: SonarCubeIssuesResponse = client.get(issuesUrl) {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }.body()
            val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            val fileCoverages = fileTree.components.map { component ->
                SonarQubeFileCoverage(
                    key = component.key,
                    path = component.path ?: component.key.substringAfter("$projectKey:"),
                    name = component.name,
                    coverage = component.measureValue("coverage")?.toDoubleOrNull(),
                    uncoveredLines = component.measureValue("uncovered_lines")?.toIntOrNull()
                )
            }.sortedBy { it.coverage ?: 100.0 }
            return SonarCubeResult(
                projectKey = config.projectKey,
                serverUrl = config.serverUrl,
                coverage = measures.component.measureValue("coverage")?.toDoubleOrNull(),
                lineCoverage = measures.component.measureValue("line_coverage")?.toDoubleOrNull(),
                branchCoverage = measures.component.measureValue("branch_coverage")?.toDoubleOrNull(),
                uncoveredLines = measures.component.measureValue("uncovered_lines")?.toIntOrNull(),
                bugs = measures.component.measureValue("bugs")?.toIntOrNull(),
                vulnerabilities = measures.component.measureValue("vulnerabilities")?.toIntOrNull(),
                codeSmells = measures.component.measureValue("code_smells")?.toIntOrNull(),
                issues = issues.issues.map { it.toDomain(config.projectKey) },
                reportTimestamp = now,
                fileCoverages = fileCoverages
            )
        } finally {
            client.close()
        }
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

internal data class SonarCubeResult(
    val projectKey: String,
    val serverUrl: String,
    val coverage: Double?,
    val lineCoverage: Double?,
    val branchCoverage: Double?,
    val uncoveredLines: Int?,
    val bugs: Int?,
    val vulnerabilities: Int?,
    val codeSmells: Int?,
    val issues: List<SonarCubeIssue>,
    val reportTimestamp: String? = null,
    val fileCoverages: List<SonarQubeFileCoverage> = emptyList()
)

internal data class SonarCubeIssue(
    val key: String,
    val path: String,
    val line: Int?,
    val severity: String,
    val type: String,
    val rule: String,
    val message: String
) {
    val locationText: String
        get() = if (line != null) "$path:$line" else path
}

@Serializable
private data class SonarCubeMeasuresResponse(val component: SonarCubeComponent)

@Serializable
private data class SonarCubeComponent(
    val key: String,
    val name: String = key,
    val path: String? = null,
    val measures: List<SonarCubeMeasure> = emptyList()
) {
    fun measureValue(metric: String): String? = measures.firstOrNull { it.metric == metric }?.value
}

@Serializable
private data class SonarCubeMeasure(val metric: String, val value: String? = null)

@Serializable
private data class SonarCubeComponentTreeResponse(val components: List<SonarCubeComponent> = emptyList())

@Serializable
private data class SonarCubeIssuesResponse(val issues: List<SonarCubeIssueDto> = emptyList())

@Serializable
private data class SonarCubeIssueDto(
    val key: String,
    val component: String,
    val line: Int? = null,
    val textRange: SonarCubeTextRange? = null,
    val severity: String = "UNKNOWN",
    val type: String = "ISSUE",
    val rule: String = "",
    val message: String = ""
) {
    fun toDomain(projectKey: String): SonarCubeIssue {
        val prefix = "$projectKey:"
        return SonarCubeIssue(
            key = key,
            path = component.removePrefix(prefix),
            line = line ?: textRange?.startLine,
            severity = severity,
            type = type,
            rule = rule,
            message = message
        )
    }
}

@Serializable
private data class SonarCubeTextRange(val startLine: Int? = null)

private class SonarCubeIssueRenderer(
    private val fixedKeys: MutableSet<String>
) : DefaultListCellRenderer() {

    private fun severityColor(severity: String): String = when (severity.uppercase()) {
        "BLOCKER" -> "#D32F2F"
        "CRITICAL" -> "#E65100"
        "MAJOR" -> "#F9A825"
        "MINOR" -> "#388E3C"
        "INFO" -> "#1976D2"
        else -> "#94A3B8"
    }

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        val issue = value as? SonarCubeIssue
        if (issue != null) {
            val isFixed = issue.key in fixedKeys
            val color = if (isFixed) "#388E3C" else severityColor(issue.severity)
            val borderColor = if (isFixed) "#2E7D32" else color
            val severityBadge = if (isFixed) {
                "<span style='color:#A5D6A7;font-weight:bold'>FIXED</span>"
            } else {
                "<span style='color:$color;font-weight:bold'>${issue.severity}</span>"
            }
            val typeBadge = "[${issue.type}]"
            val textColor = if (isFixed) "#888" else "#E0E0E0"
            val msgColor = if (isFixed) "#777" else "#B0B0B0"
            val ruleColor = if (isFixed) "#666" else "#94A3B8"
            val decoration = if (isFixed) "text-decoration:line-through;" else ""
            label.text = "<html>" +
                "<table cellpadding='0' cellspacing='0' style='border-left:3px solid $borderColor;padding-left:6px;width:100%'>" +
                "<tr><td>$severityBadge $typeBadge " +
                "<span style='color:$textColor;$decoration'>${issue.locationText}</span></td></tr>" +
                "<tr><td style='color:$msgColor;$decoration'>${escapeHtml(issue.message)}</td></tr>" +
                "<tr><td style='color:$ruleColor;font-size:90%'>${issue.rule}</td></tr>" +
                "</table></html>"
        }
        label.border = BorderFactory.createEmptyBorder(3, 4, 3, 8)
        label.font = UIManager.getFont("Label.font") ?: label.font
        return label
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private fun Double.formatPercent(): String = String.format(Locale.US, "%.2f%%", this)
