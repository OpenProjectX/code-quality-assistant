package org.openprojectx.ai.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class LlmLoginDialog(project: Project) : DialogWrapper(project) {

    private val usernameField = JTextField()
    private val passwordField = JPasswordField()

    init {
        title = "LLM Login"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
        panel.add(labeled("Username", usernameField))
        panel.add(labeled("Password", passwordField))
        return panel
    }

    fun credentials(): LoginCredentials = LoginCredentials(
        username = usernameField.text.trim(),
        password = String(passwordField.password)
    )

    private fun labeled(label: String, component: JComponent): JComponent {
        val panel = JPanel(BorderLayout(8, 0))
        panel.add(JLabel(label), BorderLayout.WEST)
        panel.add(component, BorderLayout.CENTER)
        return panel
    }
}

data class LoginCredentials(
    val username: String,
    val password: String
)
