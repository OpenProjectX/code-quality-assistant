package org.openprojectx.ai.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.UIManager

object SonarCubeToolWindowPanel {
    fun create(project: Project, bgColor: Color, fgColor: Color, borderColor: Color, commonFont: Font): JPanel {
        val issueListModel = DefaultListModel<SonarCubeIssue>()
        val summaryArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = commonFont
            background = bgColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            text = "Click Refresh to load configured SonarQube results."
        }
        val issueList = JList(issueListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            background = Color(0x11, 0x1C, 0x2F)
            foreground = fgColor
            fixedCellHeight = 54
            cellRenderer = SonarCubeIssueRenderer()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount >= 2) {
                        selectedValue?.let { openIssue(project, it) }
                    }
                }
            })
        }
        val refreshButton = JButton("Refresh")
        val openButton = JButton("Open Selected")

        fun load() {
            refreshButton.isEnabled = false
            openButton.isEnabled = false
            issueListModel.clear()
            summaryArea.text = "Loading SonarQube results..."
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Load SonarQube Results", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Reading SonarQube configuration..."
                        val config = LlmSettingsLoader.loadSonarQubeConfig(project)
                        if (config.serverUrl.isBlank() || config.projectKey.isBlank()) {
                            ApplicationManager.getApplication().invokeLater {
                                summaryArea.text = "SonarQube is not configured. Add sonarQube.serverUrl and sonarQube.projectKey to .ai-test.yaml."
                                refreshButton.isEnabled = true
                                openButton.isEnabled = true
                            }
                            return
                        }

                        indicator.text = "Fetching SonarQube issues..."
                        val result = runBlocking { SonarCubeToolWindowClient(config).load() }
                        ApplicationManager.getApplication().invokeLater {
                            summaryArea.text = SonarCubeResultRenderer.render(result)
                            result.issues.forEach(issueListModel::addElement)
                            refreshButton.isEnabled = true
                            openButton.isEnabled = true
                        }
                    } catch (ex: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            summaryArea.text = "Failed to load SonarQube results: ${ex.message ?: ex.toString()}"
                            refreshButton.isEnabled = true
                            openButton.isEnabled = true
                            Notifications.error(project, "SonarQube Results failed", ex.message ?: ex.toString())
                        }
                    }
                }
            })
        }

        refreshButton.addActionListener { load() }
        openButton.addActionListener {
            issueList.selectedValue?.let { openIssue(project, it) }
        }

        val root = JPanel(BorderLayout(8, 8)).apply {
            background = bgColor
            foreground = fgColor
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JLabel("Sonar Cube").apply { foreground = fgColor }, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                    add(openButton)
                    add(refreshButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(com.intellij.ui.components.JBScrollPane(summaryArea).apply {
                preferredSize = Dimension(320, 150)
                viewport.background = bgColor
                border = BorderFactory.createLineBorder(borderColor)
            }, BorderLayout.CENTER)
            add(com.intellij.ui.components.JBScrollPane(issueList).apply {
                preferredSize = Dimension(320, 360)
                viewport.background = Color(0x11, 0x1C, 0x2F)
                border = BorderFactory.createLineBorder(borderColor)
            }, BorderLayout.SOUTH)
        }
        load()
        return root
    }

    private fun openIssue(project: Project, issue: SonarCubeIssue) {
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            Notifications.warn(project, "Sonar Cube", "Cannot open file because project base path is unknown.")
            return
        }
        val file = java.io.File(basePath, issue.path)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (virtualFile == null) {
            Notifications.warn(project, "Sonar Cube", "Cannot find local file: ${issue.path}")
            return
        }
        val lineIndex = issue.line?.minus(1)?.coerceAtLeast(0) ?: 0
        OpenFileDescriptor(project, virtualFile, lineIndex, 0).navigate(true)
    }
}

private class SonarCubeToolWindowClient(private val config: SonarQubeConfig) {
    private val authHeader = config.resolvedToken.takeIf { it.isNotBlank() }?.let {
        "Basic " + Base64.getEncoder().encodeToString("$it:".toByteArray(StandardCharsets.UTF_8))
    }

    suspend fun load(): SonarCubeResult {
        val client = HttpClients.shared(timeoutSeconds = 60)
        try {
            val baseUrl = config.serverUrl.trimEnd('/')
            val projectKey = encoded(config.projectKey)
            val measures: SonarCubeMeasuresResponse = client.get(
                "$baseUrl/api/measures/component?component=$projectKey&metricKeys=coverage,line_coverage,branch_coverage,uncovered_lines,bugs,vulnerabilities,code_smells"
            ) {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }.body()
            val issues: SonarCubeIssuesResponse = client.get(
                "$baseUrl/api/issues/search?componentKeys=$projectKey&resolved=false&ps=100&s=SEVERITY&asc=false"
            ) {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }.body()
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
                issues = issues.issues.map { it.toDomain(config.projectKey) }
            )
        } finally {
            client.close()
        }
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

private data class SonarCubeResult(
    val projectKey: String,
    val serverUrl: String,
    val coverage: Double?,
    val lineCoverage: Double?,
    val branchCoverage: Double?,
    val uncoveredLines: Int?,
    val bugs: Int?,
    val vulnerabilities: Int?,
    val codeSmells: Int?,
    val issues: List<SonarCubeIssue>
)

private data class SonarCubeIssue(
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
    val measures: List<SonarCubeMeasure> = emptyList()
) {
    fun measureValue(metric: String): String? = measures.firstOrNull { it.metric == metric }?.value
}

@Serializable
private data class SonarCubeMeasure(val metric: String, val value: String? = null)

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

private object SonarCubeResultRenderer {
    fun render(result: SonarCubeResult): String = buildString {
        appendLine("Project: ${result.projectKey}")
        appendLine("Server: ${result.serverUrl}")
        appendLine("Coverage: ${result.coverage?.formatPercent() ?: "n/a"}")
        appendLine("Line coverage: ${result.lineCoverage?.formatPercent() ?: "n/a"}")
        appendLine("Branch coverage: ${result.branchCoverage?.formatPercent() ?: "n/a"}")
        appendLine("Uncovered lines: ${result.uncoveredLines ?: 0}")
        appendLine("Bugs: ${result.bugs ?: 0}")
        appendLine("Vulnerabilities: ${result.vulnerabilities ?: 0}")
        appendLine("Code smells: ${result.codeSmells ?: 0}")
        appendLine("Open issues: ${result.issues.size}")
        appendLine()
        appendLine("Double-click an issue below, or select it and click Open Selected, to jump to the local source line.")
    }.trimEnd()
}

private class SonarCubeIssueRenderer : DefaultListCellRenderer() {
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
            label.text = "<html><b>${issue.severity}</b> [${issue.type}] ${issue.locationText}<br/>${escapeHtml(issue.message)}<br/><span style='color:#94A3B8'>${issue.rule}</span></html>"
        }
        label.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
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
