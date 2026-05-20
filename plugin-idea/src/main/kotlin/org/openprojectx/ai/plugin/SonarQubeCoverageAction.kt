package org.openprojectx.ai.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindowManager
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class SonarQubeCoverageAction : AnAction("SonarQube Coverage"), DumbAware {

    init {
        templatePresentation.icon = OpenProjectXIcons.GenerateTests
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = runCatching { LlmSettingsLoader.loadConfig(project).sonarQube }
            .getOrElse { SonarQubeConfig() }
        val dialog = SonarQubeCoverageDialog(project, config)
        if (!dialog.showAndGet()) return

        val request = dialog.request()
        if (request.serverUrl.isBlank() || request.projectKey.isBlank()) {
            Notifications.warn(project, "SonarQube Coverage", "Please provide SonarQube server URL and project key.")
            return
        }

        ButtonUsageReportService.getInstance(project).record("sonarqube.coverage")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "SonarQube Coverage", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Loading SonarQube coverage..."
                    val report = runBlocking { SonarQubeCoverageClient(request).loadCoverage() }
                    val coverageSummary = SonarQubeCoverageRenderer.render(report, request.targetCoverage)

                    val generation = if (request.generateMissingTests) {
                        indicator.text = "Generating tests for uncovered code..."
                        val prompt = SonarQubeCoveragePromptBuilder.build(project, report, request.targetCoverage)
                        val provider = LlmProviderFactory.create(LlmSettingsLoader.load(project))
                        runBlocking { provider.generateCode(prompt) }
                    } else {
                        ""
                    }

                    ApplicationManager.getApplication().invokeLater {
                        ContextBoxStateService.getInstance(project).recordSonarQubeCoverage(
                            projectKey = request.projectKey,
                            coverageSummary = coverageSummary,
                            generation = generation
                        )
                        ToolWindowManager.getInstance(project).getToolWindow("AI Context Box")?.show(null)
                    }
                } catch (ex: Exception) {
                    Notifications.error(project, "SonarQube Coverage failed", ex.message ?: ex.toString())
                }
            }
        })
    }
}

private data class SonarQubeCoverageRequest(
    val serverUrl: String,
    val projectKey: String,
    val token: String,
    val username: String,
    val password: String,
    val targetCoverage: Double,
    val maxFiles: Int,
    val generateMissingTests: Boolean,
    val skipPkixCheck: Boolean,
    val mockEnabled: Boolean
)

private class SonarQubeCoverageDialog(
    project: Project,
    config: SonarQubeConfig
) : DialogWrapper(project) {
    private val serverUrlField = JTextField(config.serverUrl, 40)
    private val projectKeyField = JTextField(config.projectKey, 40)
    private val tokenField = JTextField(config.resolvedToken, 40)
    private val usernameField = JTextField(config.username, 40)
    private val passwordField = JPasswordField(config.resolvedPassword, 40)
    private val targetCoverageField = JTextField(config.targetCoverage.toString(), 8)
    private val maxFilesField = JTextField(config.maxFiles.toString(), 8)
    private val generateMissingTestsBox = JCheckBox("Generate missing tests with AI", true)
    private val skipPkixCheckBox = JCheckBox("Skip PKIX/TLS certificate check", true)
    private val mockEnabled = config.mockEnabled

    init {
        title = "SonarQube Coverage"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JLabel("SonarQube server URL"))
        add(serverUrlField)
        add(JLabel("Project key"))
        add(projectKeyField)
        add(JLabel("Token (optional; tokenEnv can be configured in .ai-test.yaml)"))
        add(tokenField)
        add(JLabel("Username (optional; used only when token is blank)"))
        add(usernameField)
        add(JLabel("Password (optional; passwordEnv can be configured in .ai-test.yaml)"))
        add(passwordField)
        add(JLabel("Target coverage %"))
        add(targetCoverageField)
        add(JLabel("Max uncovered files to inspect"))
        add(maxFilesField)
        add(generateMissingTestsBox)
        add(skipPkixCheckBox)
    }

    fun request(): SonarQubeCoverageRequest = SonarQubeCoverageRequest(
        serverUrl = serverUrlField.text.trim(),
        projectKey = projectKeyField.text.trim(),
        token = tokenField.text.trim(),
        username = usernameField.text.trim(),
        password = String(passwordField.password).trim(),
        targetCoverage = targetCoverageField.text.trim().toDoubleOrNull() ?: 80.0,
        maxFiles = maxFilesField.text.trim().toIntOrNull()?.coerceIn(1, 20) ?: 5,
        generateMissingTests = generateMissingTestsBox.isSelected,
        skipPkixCheck = skipPkixCheckBox.isSelected,
        mockEnabled = mockEnabled
    )
}

internal data class SonarQubeCoverageReport(
    val projectKey: String,
    val projectCoverage: Double?,
    val projectLineCoverage: Double?,
    val projectBranchCoverage: Double?,
    val uncoveredLines: Int?,
    val files: List<SonarQubeFileCoverage>
)

internal data class SonarQubeFileCoverage(
    val key: String,
    val path: String,
    val name: String,
    val coverage: Double?,
    val uncoveredLines: Int?
)

private class SonarQubeCoverageClient(private val request: SonarQubeCoverageRequest) {
    private val authHeader = SonarQubeAuth.authorizationHeader(
        token = request.token,
        username = request.username,
        password = request.password
    )

    suspend fun loadCoverage(): SonarQubeCoverageReport {
        if (request.mockEnabled) {
            return SonarQubeMockData.coverageReport(request.projectKey, request.targetCoverage)
        }
        val jsonClient = HttpClients.shared(disableTlsVerification = request.skipPkixCheck, timeoutSeconds = 60)
        try {
            val baseUrl = request.serverUrl.trimEnd('/')
            val component = encoded(request.projectKey)
            val projectMeasures: SonarComponentMeasuresResponse = jsonClient.get(
                "$baseUrl/api/measures/component?component=$component&metricKeys=coverage,line_coverage,branch_coverage,uncovered_lines"
            ) {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }.body()
            val fileMeasures: SonarComponentTreeResponse = jsonClient.get(
                "$baseUrl/api/measures/component_tree?component=$component&metricKeys=coverage,uncovered_lines&qualifiers=FIL&s=metric&metricSort=uncovered_lines&asc=false&ps=${request.maxFiles.coerceIn(1, 100)}"
            ) {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }.body()

            val project = projectMeasures.component
            return SonarQubeCoverageReport(
                projectKey = project.key,
                projectCoverage = project.measureValue("coverage")?.toDoubleOrNull(),
                projectLineCoverage = project.measureValue("line_coverage")?.toDoubleOrNull(),
                projectBranchCoverage = project.measureValue("branch_coverage")?.toDoubleOrNull(),
                uncoveredLines = project.measureValue("uncovered_lines")?.toIntOrNull(),
                files = fileMeasures.components.map { component ->
                    SonarQubeFileCoverage(
                        key = component.key,
                        path = component.path ?: component.key.substringAfter(':'),
                        name = component.name,
                        coverage = component.measureValue("coverage")?.toDoubleOrNull(),
                        uncoveredLines = component.measureValue("uncovered_lines")?.toIntOrNull()
                    )
                }.filter { (it.uncoveredLines ?: 0) > 0 || (it.coverage ?: 100.0) < request.targetCoverage }
            )
        } finally {
            jsonClient.close()
        }
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

@Serializable
private data class SonarComponentMeasuresResponse(val component: SonarComponent)

@Serializable
private data class SonarComponentTreeResponse(val components: List<SonarComponent> = emptyList())

@Serializable
private data class SonarComponent(
    val key: String,
    val name: String = key,
    val path: String? = null,
    val measures: List<SonarMeasure> = emptyList()
) {
    fun measureValue(metric: String): String? = measures.firstOrNull { it.metric == metric }?.value
}

@Serializable
private data class SonarMeasure(
    val metric: String,
    val value: String? = null,
    @SerialName("bestValue") val bestValue: Boolean? = null
)

private object SonarQubeCoverageRenderer {
    fun render(report: SonarQubeCoverageReport, targetCoverage: Double): String = buildString {
        appendLine("Project: ${report.projectKey}")
        appendLine("Coverage: ${report.projectCoverage?.formatPercent() ?: "n/a"} (target ${targetCoverage.formatPercent()})")
        appendLine("Line coverage: ${report.projectLineCoverage?.formatPercent() ?: "n/a"}")
        appendLine("Branch coverage: ${report.projectBranchCoverage?.formatPercent() ?: "n/a"}")
        appendLine("Uncovered lines: ${report.uncoveredLines ?: 0}")
        appendLine()
        if (report.files.isEmpty()) {
            appendLine("No uncovered files found by SonarQube for the selected filters.")
        } else {
            appendLine("Files that need coverage:")
            report.files.forEachIndexed { index, file ->
                appendLine("${index + 1}. ${file.path} — coverage ${file.coverage?.formatPercent() ?: "n/a"}, uncovered lines ${file.uncoveredLines ?: 0}")
            }
        }
    }.trimEnd()
}

private object SonarQubeCoveragePromptBuilder {
    fun build(project: Project, report: SonarQubeCoverageReport, targetCoverage: Double): String {
        val fileContexts = report.files.joinToString("\n\n") { file ->
            val code = readProjectFile(project, file.path)
            """
            File: ${file.path}
            Current coverage: ${file.coverage?.formatPercent() ?: "n/a"}
            Uncovered lines: ${file.uncoveredLines ?: 0}
            Code:
            ```
            ${code.ifBlank { "// Local source file not found. Use the path and SonarQube metadata to propose tests." }}
            ```
            """.trimIndent()
        }
        return AiPromptDefaults.render(
            AiPromptDefaults.SONARQUBE_MISSING_TESTS,
            mapOf(
                "projectKey" to report.projectKey,
                "targetCoverage" to targetCoverage.formatPercent(),
                "coverageSummary" to SonarQubeCoverageRenderer.render(report, targetCoverage),
                "fileContexts" to fileContexts
            )
        )
    }

    private fun readProjectFile(project: Project, path: String): String {
        val root = project.basePath ?: return ""
        val file = File(root, path)
        if (!file.exists() || !file.isFile) return ""
        return file.readText(Charsets.UTF_8).take(12_000)
    }
}

private fun Double.formatPercent(): String = String.format(Locale.US, "%.2f%%", this)
