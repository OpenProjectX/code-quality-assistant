package org.openprojectx.ai.plugin

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.UIManager

class ContextBoxToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val stateService = ContextBoxStateService.getInstance(project)

        val classModel = DefaultListModel<String>()
        val classList = JBList(classModel)

        val commonFont = UIManager.getFont("Label.font")
            ?.deriveFont(Font.PLAIN, 13f)
            ?: Font("SansSerif", Font.PLAIN, 13)
        val bgColor = Color(0x0D, 0x0D, 0x0D)
        val fgColor = Color(0xFF, 0xFF, 0xFF)
        val borderColor = Color(0x22, 0x22, 0x22)

        val diffArea = JTextArea().apply {
            isEditable = false
            lineWrap = false
            font = commonFont
            background = bgColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        val branchSummaryArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = commonFont
            background = bgColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        classList.apply {
            font = commonFont
            background = bgColor
            foreground = fgColor
            selectionBackground = Color(0x1F, 0x29, 0x38)
            selectionForeground = fgColor
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }

        fun styledScrollPane(component: java.awt.Component): JBScrollPane =
            JBScrollPane(component).apply {
                viewport.background = bgColor
                background = bgColor
                border = BorderFactory.createLineBorder(borderColor)
            }

        val tabs = JTabbedPane().apply {
            addTab("Classes", styledScrollPane(classList))
            addTab("Code Diff", styledScrollPane(diffArea))
            addTab("Branch Analysis", styledScrollPane(branchSummaryArea))
            background = bgColor
            foreground = fgColor
            font = commonFont
            border = BorderFactory.createLineBorder(borderColor)
        }

        val panel = JPanel(BorderLayout()).apply {
            add(tabs, BorderLayout.CENTER)
            background = bgColor
            foreground = fgColor
        }

        fun render(snapshot: ContextBoxStateService.Snapshot) {
            classModel.removeAllElements()
            snapshot.entries.forEach {
                classModel.addElement("${it.className}  •  ${it.targetPath}  •  ${it.timestamp}")
            }
            diffArea.text = snapshot.latestDiff
            diffArea.caretPosition = 0
            branchSummaryArea.text = snapshot.latestBranchSummary
            branchSummaryArea.caretPosition = 0
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
