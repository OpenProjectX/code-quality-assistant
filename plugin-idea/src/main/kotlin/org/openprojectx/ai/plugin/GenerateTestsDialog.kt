package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import org.openprojectx.ai.plugin.core.Framework
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.*

class GenerateTestsDialog(
    private val project: Project,
    private val contractText: String
) : DialogWrapper(project) {

    private val config = LlmSettingsLoader.loadConfig(project)

    private val frameworkCombo = JComboBox(Framework.entries.toTypedArray())
    private val location = JTextField()
    private val clsField = JTextField()
    private val baseUrlField = JTextField()
    private val notesArea = JTextArea(5, 40)

    private val packageNameField = JTextField()

    private val frameworkSpecificPanel = JPanel(CardLayout())
    private val emptyFrameworkPanel = JPanel(BorderLayout())
    private val restAssuredPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(labeled("Package Name", packageNameField))
    }

    init {
        title = "Generate API Tests"
        applyDefaults(config.generation.defaultFramework)
        frameworkCombo.selectedItem = config.generation.defaultFramework
        frameworkCombo.addActionListener {
            val selected = selectedFramework()
            applyFrameworkDefaults(selected)
            updateFrameworkSpecificFields(selected)
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        val form = JPanel()
        form.layout = BoxLayout(form, BoxLayout.Y_AXIS)

        frameworkSpecificPanel.add(emptyFrameworkPanel, "empty")
        frameworkSpecificPanel.add(restAssuredPanel, Framework.REST_ASSURED.id)

        form.add(labeled("Framework", frameworkCombo))
        form.add(labeled("Location", location))
        form.add(labeled("Class Name", clsField))
        form.add(labeled("Base URL (optional)", baseUrlField))
        form.add(labeled("Extra instructions (optional)", JScrollPane(notesArea)))
        form.add(frameworkSpecificPanel)

        updateFrameworkSpecificFields(selectedFramework())

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun applyDefaults(framework: Framework) {
        val generation = config.generation
        val resolved = generation.defaultsFor(framework)

        location.text = resolved.location
        clsField.text = generation.defaultClassName
        baseUrlField.text = generation.defaultBaseUrl
        notesArea.text = generation.defaultNotes
        packageNameField.text = resolved.packageName.orEmpty()
    }

    private fun applyFrameworkDefaults(framework: Framework) {
        val resolved = config.generation.defaultsFor(framework)
        location.text = resolved.location
        packageNameField.text = resolved.packageName.orEmpty()
    }

    private fun updateFrameworkSpecificFields(framework: Framework) {
        val layout = frameworkSpecificPanel.layout as CardLayout
        when (framework) {
            Framework.REST_ASSURED -> layout.show(frameworkSpecificPanel, Framework.REST_ASSURED.id)
            Framework.KARATE -> layout.show(frameworkSpecificPanel, "empty")
        }
    }

    private fun selectedFramework(): Framework = frameworkCombo.selectedItem as? Framework
        ?: config.generation.defaultFramework

    private fun labeled(label: String, comp: JComponent): JComponent {
        val p = JPanel(BorderLayout(8, 0))
        p.add(JLabel(label), BorderLayout.WEST)
        p.add(comp, BorderLayout.CENTER)
        return p
    }

    fun result(): UiResult {
        val framework = selectedFramework()
        val frameworkConfig = when (framework) {
            Framework.REST_ASSURED -> FrameworkUiConfig.RestAssured(
                packageName = packageNameField.text.trim()
            )
            Framework.KARATE -> FrameworkUiConfig.None
        }

        return UiResult(
            framework = framework,
            location = location.text.trim(),
            className = clsField.text.trim(),
            baseUrl = baseUrlField.text.trim().ifEmpty { null },
            notes = notesArea.text.trim().ifEmpty { null },
            frameworkConfig = frameworkConfig
        )
    }

    data class UiResult(
        val framework: Framework,
        val location: String,
        val className: String,
        val baseUrl: String?,
        val notes: String?,
        val frameworkConfig: FrameworkUiConfig
    )

    companion object {
        fun open(project: Project, file: com.intellij.openapi.vfs.VirtualFile, contractText: String) {
            val dialog = GenerateTestsDialog(project, contractText)
            if (!dialog.showAndGet()) return
            GenerateTestsService(project).generate(dialog.result(), file, contractText)
        }
    }
}