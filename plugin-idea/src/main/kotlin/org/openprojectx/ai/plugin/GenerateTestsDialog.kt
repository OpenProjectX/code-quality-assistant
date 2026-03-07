package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import javax.swing.*

class GenerateTestsDialog(
    private val project: Project,
    private val contractText: String
) : DialogWrapper(project) {

    private val frameworkCombo = JComboBox(arrayOf("JUnit 5 + RestAssured", "Karate"))
    private val location = JTextField("org.openprojectx.api")
    private val clsField = JTextField("OpenApiGeneratedTests")
    private val baseUrlField = JTextField("")
    private val notesArea = JTextArea(5, 40)

    init {
        title = "Generate API Tests"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        val form = JPanel()
        form.layout = BoxLayout(form, BoxLayout.Y_AXIS)

        form.add(labeled("Framework", frameworkCombo))
        form.add(labeled("Location", this@GenerateTestsDialog.location))
        form.add(labeled("Class Name", clsField))
        form.add(labeled("Base URL (optional)", baseUrlField))
        form.add(labeled("Extra instructions (optional)", JScrollPane(notesArea)))

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun labeled(label: String, comp: JComponent): JComponent {
        val p = JPanel(BorderLayout(8, 0))
        p.add(JLabel(label), BorderLayout.WEST)
        p.add(comp, BorderLayout.CENTER)
        return p
    }

    fun result(): UiResult = UiResult(
        frameworkIndex = frameworkCombo.selectedIndex,
        location = this@GenerateTestsDialog.location.text.trim(),
        className = clsField.text.trim(),
        baseUrl = baseUrlField.text.trim().ifEmpty { null },
        notes = notesArea.text.trim().ifEmpty { null }
    )

    data class UiResult(
        val frameworkIndex: Int,
        val location: String,
        val className: String,
        val baseUrl: String?,
        val notes: String?
    )

    companion object {
        fun open(project: Project, file: com.intellij.openapi.vfs.VirtualFile, contractText: String) {
            val dialog = GenerateTestsDialog(project, contractText)
            if (!dialog.showAndGet()) return
            GenerateTestsService(project).generate(dialog.result(), file, contractText)
        }
    }
}