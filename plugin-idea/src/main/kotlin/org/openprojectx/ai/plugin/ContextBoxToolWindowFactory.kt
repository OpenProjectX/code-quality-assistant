package org.openprojectx.ai.plugin

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea

class ContextBoxToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val stateService = ContextBoxStateService.getInstance(project)

        val classModel = DefaultListModel<String>()
        val classList = JBList(classModel)

        val diffArea = JTextArea().apply {
            isEditable = false
            lineWrap = false
        }

        val tabs = JTabbedPane().apply {
            addTab("Classes", JBScrollPane(classList))
            addTab("Code Diff", JBScrollPane(diffArea))
        }

        val panel = JPanel(BorderLayout()).apply {
            add(tabs, BorderLayout.CENTER)
        }

        fun render(snapshot: ContextBoxStateService.Snapshot) {
            classModel.removeAllElements()
            snapshot.entries.forEach {
                classModel.addElement("${it.className}  •  ${it.targetPath}  •  ${it.timestamp}")
            }
            diffArea.text = snapshot.latestDiff
            diffArea.caretPosition = 0
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
}
