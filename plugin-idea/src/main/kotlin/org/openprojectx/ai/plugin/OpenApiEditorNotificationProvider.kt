package org.openprojectx.ai.plugin

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotifications
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager

class OpenApiEditorNotificationProvider : EditorNotifications.Provider<EditorNotificationPanel>() {

    private val key = Key.create<EditorNotificationPanel>("aitestgen.openapi.panel")

    override fun getKey(): Key<EditorNotificationPanel> = key

    override fun createNotificationPanel(
        file: VirtualFile,
        fileEditor: FileEditor,
        project: Project
    ): EditorNotificationPanel? {
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
        var contractText = doc.text ?: return null

        if (!OpenApiHeuristics.looksLikeOpenApi(file, contractText)) return null

        return EditorNotificationPanel(fileEditor).apply {
            text = "OpenAPI contract detected"
            icon(OpenProjectXIcons.GenerateTests) // your icon
            createActionLabel("Generate Tests By AI") {
                GenerateTestsDialog.open(project, file, contractText)
            }
        }
    }
}