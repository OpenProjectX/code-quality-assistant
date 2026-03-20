package org.openprojectx.ai.plugin

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotifications
import com.intellij.util.ui.JBUI
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class OpenApiEditorNotificationProvider : EditorNotifications.Provider<EditorNotificationPanel>() {

    private val key = Key.create<EditorNotificationPanel>("aitestgen.openapi.panel")

    override fun getKey(): Key<EditorNotificationPanel> = key

    override fun createNotificationPanel(
        file: VirtualFile,
        fileEditor: FileEditor,
        project: Project
    ): EditorNotificationPanel? {
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val contractText = doc.text ?: return null

        val isOpenApi = OpenApiHeuristics.looksLikeOpenApi(file, contractText)
        val isJavaSource = JavaHeuristics.looksLikeJavaSource(file, contractText)
        val isTestJavaSource = isJavaSource && JavaHeuristics.isTestJavaPath(file)

        if (!isOpenApi && !isJavaSource) return null

        if (isTestJavaSource) return null

        val detectedSource = if (isOpenApi) "OpenAPI contract" else "Java source"

        val stateService = OpenApiNotificationStateService.getInstance(project)
        val state = stateService.getState(file.path)

        return EditorNotificationPanel(fileEditor).apply {
            border = JBUI.Borders.empty(10, 0)

            when (state) {
                GenerationUiState.Idle -> {
                    text = "$detectedSource detected"
                    icon(OpenProjectXIcons.GenerateTests)
                }

                GenerationUiState.Generating -> {
                    text = "Generating tests with AI..."
                    icon(AnimatedIcon.Default())
                }

                GenerationUiState.Done -> {
                    text = "Tests generated successfully"
                    icon(OpenProjectXIcons.GenerateTests)
                }
            }

            val actionLabel = createActionLabel("Generate Tests By AI") {
                stateService.setState(file.path, GenerationUiState.Generating)
                EditorNotifications.getInstance(project).updateNotifications(file)

                GenerateTestsDialog.open(project, file, contractText)
            }
            actionLabel.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!e.isPopupTrigger && e.button != MouseEvent.BUTTON3) return
                    val profiles = LlmSettingsLoader.loadConfig(project).prompts.profiles.generation
                    val options = profiles.items.keys.toTypedArray()
                    if (options.isEmpty()) return
                    val selected = com.intellij.openapi.ui.Messages.showChooseDialog(
                        project,
                        "Choose test generation prompt profile",
                        "Generation Prompt Profiles",
                        null,
                        options,
                        profiles.selected
                    )
                    if (selected < 0) return
                    stateService.setState(file.path, GenerationUiState.Generating)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                    GenerateTestsDialog.open(project, file, contractText, options[selected])
                }
            })
        }
    }
}
