package org.openprojectx.ai.plugin.pr

import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.panel
import javax.swing.JCheckBox
import javax.swing.JTextField

class PullRequestOptionsPanel(
    initialCreateAfterPush: Boolean,
    initialTargetBranch: String
) {
    private val createPrCheckBox = JCheckBox().apply {
        isSelected = initialCreateAfterPush
    }

    private val targetBranchField = JTextField(initialTargetBranch)

    val panel: DialogPanel = panel {
        group("Pull Request") {
            row {
                cell(createPrCheckBox)
                label("Create Pull Request after push")
            }
            row("Target branch") {
                cell(targetBranchField)
            }
            row {
                text("Provider is auto-detected from the repository remote URL.")
            }
        }
    }

    init {
        updateState()
        createPrCheckBox.addActionListener { updateState() }
    }

    fun getOptions(): PullRequestUiOptions {
        return PullRequestUiOptions(
            createAfterPush = createPrCheckBox.isSelected,
            targetBranch = targetBranchField.text.trim().ifBlank { "main" }
        )
    }

    private fun updateState() {
        targetBranchField.isEnabled = createPrCheckBox.isSelected
    }
}