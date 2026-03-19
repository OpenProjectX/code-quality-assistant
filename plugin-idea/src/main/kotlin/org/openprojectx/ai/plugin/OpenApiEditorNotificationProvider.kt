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

        if (isTestJavaSource) {
            val looksLikeTestClass = contractText.contains("@Test") ||
                contractText.contains("org.junit") ||
                file.name.endsWith("Test.java") ||
                file.name.endsWith("Tests.java")
            if (!looksLikeTestClass || !TestDependencyInstaller.needsSetup(project)) {
                return null
            }

            return EditorNotificationPanel(fileEditor).apply {
                border = JBUI.Borders.empty(10, 0)
                text = "Test class detected. Missing JUnit/Mockito/Rest Assured dependencies."
                icon(OpenProjectXIcons.GenerateTests)
                createActionLabel("One-click Configure Test Dependencies") {
                    TestDependencyInstaller.installAndDownloadWithFeedback(project)
                }
            }
        }

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

            createActionLabel("Generate Tests By AI") {
                stateService.setState(file.path, GenerationUiState.Generating)
                EditorNotifications.getInstance(project).updateNotifications(file)

                GenerateTestsDialog.open(project, file, contractText)
            }
        }
    }
}
