package org.openprojectx.ai.plugin

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.runBlocking
import org.openprojectx.ai.plugin.llm.OpenAiCompatibleProvider
import org.openprojectx.ai.plugin.pr.AiPullRequestService
import org.openprojectx.ai.plugin.pr.GitRepositoryContextService
import java.io.File
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.JTextArea
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.UIManager
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class ContextBoxToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private const val GUIDE_TAB = "Guide"
        private const val CONTEXT_TAB = "Context"
        private const val PROMPT_MANAGER_TAB = "Prompt Manager"
        private const val SKILL_MANAGER_TAB = "Skill Manager"
        private const val SONAR_CUBE_TAB = "Sonar Cube"
        private const val LOG_TAB = "Log"
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val stateService = ContextBoxStateService.getInstance(project)

        val commonFont = UIManager.getFont("Label.font")
            ?.deriveFont(Font.PLAIN, 13f)
            ?: Font("SansSerif", Font.PLAIN, 13)
        val bgColor = ThemeColors.mainBg
        val fgColor = ThemeColors.mainFg
        val borderColor = ThemeColors.borderColor

        val inputColor = ThemeColors.inputBg
        val userBubbleColor = ThemeColors.userBubbleBg
        val userTextColor = ThemeColors.userBubbleFg
        val assistantBubbleColor = ThemeColors.assistantBubbleBg
        val assistantTextColor = ThemeColors.assistantBubbleFg
        val systemBubbleColor = ThemeColors.systemBubbleBg
        val systemAccentColor = ThemeColors.systemAccent

        val chatFont = Font("SansSerif", Font.PLAIN, 14)
        val bubbleColumns = 36

        val messageListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bgColor
        }
        val messageScrollPane = JBScrollPane(messageListPanel).apply {
            viewport.background = bgColor
            background = bgColor
            border = BorderFactory.createLineBorder(borderColor)
            verticalScrollBar.unitIncrement = 16
        }
        val chatInputField = JTextField().apply {
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
            )
            toolTipText = "Type a message and press Enter"
        }
        val sendButton = JButton("Send")
        val clearButton = JButton("Clear")

        fun buildFollowUpMessages(snapshot: ContextBoxStateService.Snapshot, userInput: String): List<OpenAiCompatibleProvider.Message> {
            val recent = snapshot.history.takeLast(10)
            val messages = mutableListOf<OpenAiCompatibleProvider.Message>()
            messages.add(OpenAiCompatibleProvider.Message("system", "You are a helpful AI assistant in an IDE context box. Answer the user's questions based on the conversation history. When generating test code, output the COMPLETE class including ALL existing tests that are still valid."))
            for (msg in recent) {
                val role = when (msg.role) {
                    ContextBoxStateService.ChatMessage.Role.USER -> "user"
                    ContextBoxStateService.ChatMessage.Role.ASSISTANT -> "assistant"
                    ContextBoxStateService.ChatMessage.Role.SYSTEM -> "assistant"
                }
                messages.add(OpenAiCompatibleProvider.Message(role, msg.content.take(2000)))
            }

            // If regenerating tests, include existing test file so LLM preserves valid tests
            val lastGenerated = recent.lastOrNull { it.testTargetPath != null }
            val existingTests = lastGenerated?.testTargetPath?.let { path ->
                try {
                    val testFile = File(project.basePath, path)
                    if (testFile.exists()) testFile.readText(Charsets.UTF_8).take(6000) else ""
                } catch (_: Exception) { "" }
            } ?: ""

            val enhancedInput = if (existingTests.isNotBlank()) {
                "$userInput\n\n--- Existing test file — keep these tests, only modify/add as needed ---\n$existingTests"
            } else userInput

            messages.add(OpenAiCompatibleProvider.Message("user", enhancedInput))
            return messages
        }

        fun calculateRows(text: String, cols: Int): Int {
            return text.lines().sumOf { line ->
                if (line.isEmpty()) 1 else (line.length + cols - 1) / cols
            }.coerceIn(1, 24) + 1
        }

        fun sanitizeTestCode(raw: String): String {
            val trimmed = raw.trim()
            val withoutFence = trimmed
                .replaceFirst(Regex("^```(?:\\w+)?\\s*\\n?"), "")
                .replaceFirst(Regex("\\n?```\\s*$"), "")
            return withoutFence.replace(Regex("\\.{3,}\\s*$"), "")
                .lines().dropLastWhile { it.isBlank() }.joinToString("\n").trim()
        }

        fun writeTestFile(project: Project, targetPath: String, code: String) {
            val projectRoot = project.guessProjectDir() ?: error("Cannot resolve project root")
            val testFile = File(projectRoot.path, targetPath)
            testFile.parentFile.mkdirs()
            testFile.writeText(code, Charsets.UTF_8)
        }

        fun createBubble(msg: ContextBoxStateService.ChatMessage, index: Int): JPanel {
            val isUser = msg.role == ContextBoxStateService.ChatMessage.Role.USER
            val isAssistant = msg.role == ContextBoxStateService.ChatMessage.Role.ASSISTANT
            val bubbleBg = when (msg.role) {
                ContextBoxStateService.ChatMessage.Role.USER -> userBubbleColor
                ContextBoxStateService.ChatMessage.Role.ASSISTANT -> assistantBubbleColor
                ContextBoxStateService.ChatMessage.Role.SYSTEM -> systemBubbleColor
            }
            val bubbleFg = when (msg.role) {
                ContextBoxStateService.ChatMessage.Role.USER -> userTextColor
                ContextBoxStateService.ChatMessage.Role.ASSISTANT -> assistantTextColor
                ContextBoxStateService.ChatMessage.Role.SYSTEM -> fgColor
            }
            val roleLabel = when (msg.role) {
                ContextBoxStateService.ChatMessage.Role.USER -> "You"
                ContextBoxStateService.ChatMessage.Role.ASSISTANT -> "AI"
                ContextBoxStateService.ChatMessage.Role.SYSTEM -> msg.typeLabel.ifBlank { "System" }
            }
            val roleColor = when (msg.role) {
                ContextBoxStateService.ChatMessage.Role.USER -> ThemeColors.userRole
                ContextBoxStateService.ChatMessage.Role.ASSISTANT -> ThemeColors.assistantRole
                ContextBoxStateService.ChatMessage.Role.SYSTEM -> systemAccentColor
            }
            val timestampColor = when (msg.role) {
                ContextBoxStateService.ChatMessage.Role.USER -> ThemeColors.userTimestamp
                ContextBoxStateService.ChatMessage.Role.ASSISTANT -> ThemeColors.assistantTimestamp
                ContextBoxStateService.ChatMessage.Role.SYSTEM -> ThemeColors.systemTimestamp
            }

            val originalContent = msg.content
            val editBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.systemAccent, 1),
                BorderFactory.createEmptyBorder(3, 5, 3, 5)
            )
            val readOnlyBorder = BorderFactory.createEmptyBorder(4, 6, 4, 6)

            val contentArea = JTextArea().apply {
                text = msg.content
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                columns = bubbleColumns
                rows = calculateRows(msg.content, bubbleColumns)
                font = chatFont
                background = bubbleBg
                foreground = bubbleFg
                caretColor = bubbleFg
                border = readOnlyBorder
            }

            val inner = object : JPanel(BorderLayout(0, 3)) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = background
                    g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
                    g2.dispose()
                }
            }.apply {
                background = bubbleBg
                isOpaque = false
                val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(JLabel(roleLabel).apply {
                        foreground = roleColor
                        font = chatFont.deriveFont(Font.BOLD, 11f)
                    })
                    add(JLabel("  ${msg.formattedTime}").apply {
                        foreground = timestampColor
                        font = chatFont.deriveFont(Font.PLAIN, 10f)
                    })
                }
                add(header, BorderLayout.NORTH)
                add(contentArea, BorderLayout.CENTER)

                // Action bar: delete / resend / edit
                val actionBar = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(2, 0, 0, 0)

                    fun makeIcon(text: String, tooltip: String, hoverColor: Color, onClick: () -> Unit): JLabel {
                        return JLabel(text).apply {
                            this.toolTipText = tooltip
                            foreground = ThemeColors.systemTimestamp
                            font = chatFont.deriveFont(Font.PLAIN, 11f)
                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
                            addMouseListener(object : MouseAdapter() {
                                override fun mouseEntered(e: MouseEvent) { foreground = hoverColor }
                                override fun mouseExited(e: MouseEvent) { foreground = ThemeColors.systemTimestamp }
                                override fun mouseClicked(e: MouseEvent) { onClick() }
                            })
                        }
                    }

                    var editIcon: JLabel? = null
                    fun cancelEditing() {
                        contentArea.isEditable = false
                        contentArea.text = originalContent
                        contentArea.background = bubbleBg
                        contentArea.foreground = bubbleFg
                        contentArea.border = readOnlyBorder
                        editIcon?.text = "✎"
                        editIcon?.toolTipText = "Edit in place"
                    }
                    fun confirmEditing() {
                        val edited = contentArea.text.trim()
                        if (edited.isBlank()) return
                        contentArea.isEditable = false
                        val snapshot = stateService.snapshot()
                        if (index + 1 < snapshot.history.size &&
                            snapshot.history[index + 1].role == ContextBoxStateService.ChatMessage.Role.ASSISTANT
                        ) {
                            stateService.removeMessage(index + 1)
                            stateService.removeMessage(index)
                        } else {
                            stateService.removeMessage(index)
                        }
                        chatInputField.text = edited
                        sendButton.doClick()
                    }
                    if (isUser) {
                        editIcon = makeIcon("✎", "Edit in place", ThemeColors.systemAccent) {
                            if (contentArea.isEditable) {
                                confirmEditing()
                            } else {
                                contentArea.isEditable = true
                                contentArea.background = bubbleBg.brighter()
                                contentArea.border = editBorder
                                contentArea.requestFocusInWindow()
                                editIcon?.text = "✓"
                                editIcon?.toolTipText = "Confirm (Esc to cancel)"
                                // Escape key to cancel
                                contentArea.addKeyListener(object : java.awt.event.KeyAdapter() {
                                    override fun keyPressed(e: java.awt.event.KeyEvent) {
                                        if (e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE) {
                                            cancelEditing()
                                            contentArea.removeKeyListener(this)
                                        }
                                    }
                                })
                            }
                        }
                        add(editIcon!!)
                    }
                    if (isAssistant) {
                        add(makeIcon("↻", "Regenerate", ThemeColors.systemAccent) {
                            val prevUser = stateService.snapshot().history.getOrNull(index - 1)
                            if (prevUser?.role == ContextBoxStateService.ChatMessage.Role.USER) {
                                stateService.removeMessage(index)
                                chatInputField.text = prevUser.content
                                sendButton.doClick()
                            }
                        })
                    }
                    add(makeIcon("✕", "Delete", ThemeColors.deleteRed) {
                        stateService.removeMessage(index)
                    })
                }

                // Collect any action buttons below the action bar
                val extraButtons = mutableListOf<JButton>()

                if (msg.typeLabel == "Branch Analysis" && msg.sourceBranch != null && msg.targetBranch != null) {
                    extraButtons.add(JButton("Create PR →").apply {
                        font = chatFont.deriveFont(Font.BOLD, 12f)
                        foreground = ThemeColors.systemAccent
                        background = bubbleBg
                        isOpaque = false
                        border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
                        isContentAreaFilled = false
                        isFocusPainted = false
                        addActionListener {
                            isEnabled = false
                            text = "Creating PR..."
                            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Pull Request", false) {
                                override fun run(indicator: ProgressIndicator) {
                                    try {
                                        val src = msg.sourceBranch
                                        val tgt = msg.targetBranch
                                        val ctx = GitRepositoryContextService.resolve(project)
                                        indicator.text = "Collecting diff for $src vs $tgt..."
                                        val diff = GitDiffProvider.getDiffBetweenBranches(project, src, tgt)
                                        indicator.text = "Creating PR..."
                                        val result = AiPullRequestService(project).createAfterPush(
                                            remoteUrl = ctx.remoteUrl,
                                            sourceBranch = src,
                                            targetBranch = tgt,
                                            diff = diff
                                        )
                                        ApplicationManager.getApplication().invokeLater {
                                            Notifications.info(project, "PR Created", result.url)
                                        }
                                    } catch (ex: Exception) {
                                        ApplicationManager.getApplication().invokeLater {
                                            Notifications.error(project, "PR Creation Failed", ex.message ?: ex.toString())
                                        }
                                    } finally {
                                        ApplicationManager.getApplication().invokeLater {
                                            isEnabled = true
                                            text = "Create PR →"
                                        }
                                    }
                                }
                            })
                        }
                    })
                }

                val hasTestCode = msg.role == ContextBoxStateService.ChatMessage.Role.ASSISTANT && (
                    msg.typeLabel == "Generated Code" ||
                    msg.testClassName != null ||
                    (msg.content.contains("@Test") && msg.content.contains("class "))
                )
                if (hasTestCode) {
                    extraButtons.add(JButton("Generate Tests →").apply {
                        font = chatFont.deriveFont(Font.BOLD, 12f)
                        foreground = ThemeColors.systemAccent
                        background = bubbleBg
                        isOpaque = false
                        border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
                        isContentAreaFilled = false
                        isFocusPainted = false
                        addActionListener {
                            isEnabled = false
                            text = "Writing..."
                            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Writing test file", true) {
                                override fun run(indicator: ProgressIndicator) {
                                    try {
                                        indicator.text = "Writing test file..."
                                        indicator.isIndeterminate = true
                                        val targetPath = msg.testTargetPath
                                            ?: stateService.snapshot().history.lastOrNull { it.testTargetPath != null }?.testTargetPath
                                            ?: error("Cannot find test target path")
                                        val code = sanitizeTestCode(msg.content)
                                        writeTestFile(project, targetPath, code)
                                        ApplicationManager.getApplication().invokeLater {
                                            Notifications.info(project, "Tests Updated", targetPath)
                                        }
                                    } catch (ex: Exception) {
                                        ApplicationManager.getApplication().invokeLater {
                                            Notifications.error(project, "Test Generation Failed", ex.message ?: ex.toString())
                                        }
                                    } finally {
                                        ApplicationManager.getApplication().invokeLater {
                                            isEnabled = true
                                            text = "Generate Tests →"
                                        }
                                    }
                                }
                            })
                        }
                    })
                }

                if (extraButtons.isNotEmpty()) {
                    val southPanel = JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                        add(actionBar)
                        extraButtons.forEach { add(it) }
                    }
                    add(southPanel, BorderLayout.SOUTH)
                } else {
                    add(actionBar, BorderLayout.SOUTH)
                }

                border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            }

            val maxBubbleWidth = bubbleColumns * chatFont.size + 40
            inner.maximumSize = Dimension(maxBubbleWidth, inner.preferredSize.height.coerceAtLeast(1))

            val outer = JPanel(BorderLayout()).apply {
                isOpaque = false
                val spacerWest = if (isUser) JPanel().apply {
                    isOpaque = false; preferredSize = Dimension(40, 0)
                } else null
                val spacerEast = if (isUser) null else JPanel().apply {
                    isOpaque = false; preferredSize = Dimension(40, 0)
                }
                if (spacerWest != null) add(spacerWest, BorderLayout.WEST)
                if (spacerEast != null) add(spacerEast, BorderLayout.EAST)
                val aligned = JPanel(FlowLayout(if (isUser) FlowLayout.RIGHT else FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(inner)
                }
                add(aligned, BorderLayout.CENTER)
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), preferredSize.height.coerceAtLeast(1))
            }
            return outer
        }

        fun render(snapshot: ContextBoxStateService.Snapshot) {
            messageListPanel.removeAll()
            if (snapshot.history.isEmpty()) {
                messageListPanel.add(JLabel("No messages yet.").apply {
                    foreground = ThemeColors.emptyText
                    font = chatFont.deriveFont(Font.ITALIC, 13f)
                    border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
                    horizontalAlignment = SwingConstants.CENTER
                })
            } else {
                snapshot.history.forEachIndexed { index, msg ->
                    messageListPanel.add(createBubble(msg, index))
                    messageListPanel.add(Box.createVerticalStrut(6))
                }
                messageListPanel.add(Box.createVerticalGlue())
            }
            messageListPanel.revalidate()
            messageListPanel.repaint()
            javax.swing.SwingUtilities.invokeLater {
                val bar = messageScrollPane.verticalScrollBar
                bar.value = bar.maximum
            }
        }

        clearButton.addActionListener {
            stateService.clearHistory()
        }
        sendButton.addActionListener {
            val userInput = chatInputField.text.trim()
            if (userInput.isBlank()) return@addActionListener
            val snapshot = stateService.snapshot()
            val messages = buildFollowUpMessages(snapshot, userInput)

            // Show user bubble immediately
            stateService.addUserMessage(userInput)
            chatInputField.text = ""
            sendButton.isEnabled = false
            chatInputField.isEnabled = false

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Chat", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Thinking..."
                        val response = LlmAuthSessionService.getInstance(project).withReloginOnUnauthorized { settings ->
                            val provider = LlmProviderFactory.create(settings)
                            runBlocking { provider.generateCode(messages) }
                        }
                        // Show AI response bubble after LLM returns
                        ApplicationManager.getApplication().invokeLater {
                            stateService.addAssistantMessage(response)
                            sendButton.isEnabled = true
                            chatInputField.isEnabled = true
                            chatInputField.requestFocusInWindow()
                        }
                    } catch (ex: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            stateService.addAssistantMessage("Chat failed: ${ex.message ?: ex.toString()}")
                            sendButton.isEnabled = true
                            chatInputField.isEnabled = true
                            Notifications.error(project, "Chat failed", ex.message ?: ex.toString())
                        }
                    }
                }
            })
        }
        chatInputField.addActionListener { sendButton.doClick() }

        val chatPanel = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JLabel("Chat").apply {
                    foreground = fgColor
                    font = commonFont.deriveFont(Font.BOLD, 14f)
                }, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(clearButton)
                }, BorderLayout.EAST)
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }, BorderLayout.NORTH)
            add(messageScrollPane, BorderLayout.CENTER)
            add(JPanel(BorderLayout(8, 0)).apply {
                border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
                add(chatInputField, BorderLayout.CENTER)
                add(sendButton, BorderLayout.EAST)
                isOpaque = false
            }, BorderLayout.SOUTH)
            background = bgColor
            foreground = fgColor
        }

        val initialSnapshot = stateService.snapshot()
        render(initialSnapshot)

        fun selectTab(tabs: JTabbedPane, title: String) {
            for (index in 0 until tabs.tabCount) {
                if (tabs.getTitleAt(index) == title) {
                    tabs.selectedIndex = index
                    return
                }
            }
        }

        val tabs = JTabbedPane().apply {
            insertTab(GUIDE_TAB, OpenProjectXIcons.GenerateTests, createReadmePanel(project, bgColor, fgColor, borderColor, commonFont), "Feature guide and setup progress", 0)
            addTab(CONTEXT_TAB, chatPanel)
            addTab(PROMPT_MANAGER_TAB, createPromptManagerPanel(project, bgColor, fgColor, borderColor, commonFont))
            addTab(SKILL_MANAGER_TAB, createSkillManagerPanel(project, bgColor, fgColor, borderColor, commonFont))
            addTab(SONAR_CUBE_TAB, SonarCubeToolWindowPanel.create(project, bgColor, fgColor, borderColor, commonFont))
            if (LlmSettingsLoader.loadSettingsModel(project).showLogTab) {
                addTab(LOG_TAB, createLogPanel(bgColor, fgColor, borderColor, commonFont))
            }
        }
        // Some actions record their result before showing the tool window. In that case,
        // open Context immediately instead of hiding the existing result behind Guide.
        if (initialSnapshot.history.isNotEmpty()) {
            selectTab(tabs, CONTEXT_TAB)
        }

        // Only newly appended messages should focus Context. Clearing or re-rendering
        // history must not unexpectedly pull users away from Guide or manager tabs.
        var previousHistorySize = initialSnapshot.history.size
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ContextBoxStateService.TOPIC,
            ContextBoxListener { snapshot ->
                render(snapshot)
                if (snapshot.history.size > previousHistorySize) {
                    selectTab(tabs, CONTEXT_TAB)
                }
                previousHistorySize = snapshot.history.size
            }
        )

        val content = ContentFactory.getInstance().createContent(tabs, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private enum class PromptCategory(val label: String) {
        TEST("Test Generate"),
        COMMIT("Commit Generate"),
        BRANCH_DIFF("Branch Compare"),
        CODE_GENERATE("Code Generate"),
        CODE_REVIEW("Code Review");

        val globalCategoryKey: String
            get() = when (this) {
                TEST -> "test"
                COMMIT -> "commit"
                BRANCH_DIFF -> "branchDiff"
                CODE_GENERATE -> "codeGenerate"
                CODE_REVIEW -> "codeReview"
            }

        override fun toString(): String = label
    }

    private enum class PromptSort(val label: String) {
        TYPE("Sort by: Type"),
        NAME("Sort by: Name"),
        UPDATED("Sort by: Updated");

        override fun toString(): String = label
    }

    private data class PromptDefinition(
        val category: PromptCategory,
        val name: String,
        val content: String,
        val isGlobal: Boolean,
        val updatedText: String = "—"
    ) {
        val displayName: String
            get() = name.removePrefix("global/").replace(Regex("\\s*\\[[^]]+]$"), "")
    }

    private sealed class PromptListRow {
        data class All(val count: Int) : PromptListRow()
        data class CategoryHeader(val category: PromptCategory, val count: Int, val collapsed: Boolean) : PromptListRow()
        data class PromptRow(val prompt: PromptDefinition) : PromptListRow()
        object CustomHeader : PromptListRow()
    }

    private fun categoryIcon(category: PromptCategory): String = when (category) {
        PromptCategory.TEST -> "⚗"
        PromptCategory.COMMIT -> "⌘"
        PromptCategory.BRANCH_DIFF -> "⑂"
        PromptCategory.CODE_GENERATE -> "</>"
        PromptCategory.CODE_REVIEW -> "▣"
    }

    private fun categoryColor(category: PromptCategory): Color = when (category) {
        PromptCategory.TEST -> ThemeColors.categoryTest
        PromptCategory.COMMIT -> ThemeColors.categoryCommit
        PromptCategory.BRANCH_DIFF -> ThemeColors.categoryBranchDiff
        PromptCategory.CODE_GENERATE -> ThemeColors.categoryCodeGen
        PromptCategory.CODE_REVIEW -> ThemeColors.categoryCodeReview
    }

    private fun scopeColor(isGlobal: Boolean): Color =
        if (isGlobal) Color(0x60, 0xA5, 0xFA) else Color(0x34, 0xD3, 0x99)

    private fun createPromptManagerPanel(
        project: Project,
        bgColor: Color,
        fgColor: Color,
        borderColor: Color,
        commonFont: Font
    ): Component {
        val usage = ButtonUsageReportService.getInstance(project)
        val pageColor = ThemeColors.pageBg
        val surfaceColor = ThemeColors.surfaceBg
        val inputColor = ThemeColors.inputBg
        val accentColor = ThemeColors.accentBlue
        val mutedColor = ThemeColors.mutedFg
        val cardColor = ThemeColors.cardBg
        val designBorderColor = borderColor
        val promptFont = UIManager.getFont("EditorPane.font")
            ?.deriveFont(Font.PLAIN, 14f)
            ?: Font("JetBrains Mono", Font.PLAIN, 14)
        val listModel = DefaultListModel<PromptListRow>()
        val collapsedCategories = mutableSetOf<PromptCategory>()
        var selectedPrompt: PromptDefinition? = null

        val searchField = JTextField().apply {
            toolTipText = "Search prompts"
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(designBorderColor),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
        }
        val typeFilter = JComboBox(arrayOf("All Types") + PromptCategory.entries.map { it.label }.toTypedArray()).apply {
            background = inputColor
            foreground = fgColor
        }
        val sortField = JComboBox(PromptSort.entries.toTypedArray()).apply {
            background = inputColor
            foreground = fgColor
        }
        val promptList = JList(listModel).apply {
            font = commonFont
            background = surfaceColor
            foreground = fgColor
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectionBackground = accentColor
            selectionForeground = Color.WHITE
            fixedCellHeight = -1
            cellRenderer = PromptListCellRenderer(surfaceColor, fgColor, mutedColor, designBorderColor, accentColor, commonFont)
        }
        val categoryField = JComboBox(PromptCategory.entries.toTypedArray()).apply {
            background = inputColor
            foreground = fgColor
            setRenderer(object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                    if (value is PromptCategory) {
                        label.text = "${categoryIcon(value)}  ${value.label}"
                        if (!isSelected) label.foreground = categoryColor(value)
                    }
                    return label
                }
            })
        }
        val nameField = JTextField().apply {
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(designBorderColor),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
        }
        val contentField = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            font = promptFont
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
        }
        val titleLabel = JLabel("Select a prompt").apply {
            foreground = fgColor
            font = commonFont.deriveFont(Font.PLAIN, 18f)
        }
        val scopeLabel = JLabel("—").apply { foreground = mutedColor }
        val detailsPanel = JPanel(GridBagLayout()).apply {
            background = surfaceColor
            border = BorderFactory.createLineBorder(designBorderColor)
        }
        val contentPreview = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = promptFont
            background = cardColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        val viewCards = JPanel(CardLayout()).apply { background = pageColor }

        fun mapForCategory(model: AiTestSettingsModel, category: PromptCategory): LinkedHashMap<String, String> {
            val text = when (category) {
                PromptCategory.TEST -> model.generationPromptProfilesYaml
                PromptCategory.COMMIT -> model.commitPromptProfilesYaml
                PromptCategory.BRANCH_DIFF -> model.branchDiffPromptProfilesYaml
                PromptCategory.CODE_GENERATE -> model.codeGeneratePromptProfilesYaml
                PromptCategory.CODE_REVIEW -> model.codeReviewPromptProfilesYaml
            }
            val parsed = Yaml().load<Any?>(text) as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val result = linkedMapOf<String, String>()
            parsed.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString().orEmpty()
                if (key.isNotBlank() && value.isNotBlank()) result[key] = value
            }
            return LinkedHashMap(result)
        }

        fun dumpYaml(value: Map<String, String>): String {
            val options = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                indent = 2
                isPrettyFlow = true
            }
            return Yaml(options).dump(value).trimEnd()
        }

        fun mapsForModel(model: AiTestSettingsModel): Map<PromptCategory, LinkedHashMap<String, String>> = mapOf(
            PromptCategory.TEST to mapForCategory(model, PromptCategory.TEST),
            PromptCategory.COMMIT to mapForCategory(model, PromptCategory.COMMIT),
            PromptCategory.BRANCH_DIFF to mapForCategory(model, PromptCategory.BRANCH_DIFF),
            PromptCategory.CODE_GENERATE to mapForCategory(model, PromptCategory.CODE_GENERATE),
            PromptCategory.CODE_REVIEW to mapForCategory(model, PromptCategory.CODE_REVIEW)
        )

        fun updateModelFromMaps(
            model: AiTestSettingsModel,
            maps: Map<PromptCategory, LinkedHashMap<String, String>>,
            suppressed: List<String> = model.suppressedGlobalPrompts
        ): AiTestSettingsModel = model.copy(
            generationPromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.TEST)),
            commitPromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.COMMIT)),
            branchDiffPromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.BRANCH_DIFF)),
            codeGeneratePromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.CODE_GENERATE)),
            codeReviewPromptProfilesYaml = dumpYaml(maps.getValue(PromptCategory.CODE_REVIEW)),
            suppressedGlobalPrompts = suppressed.distinct().sorted()
        )

        fun updatedText(name: String): String {
            val match = Regex("\\[([^]]+)]$").find(name)?.groupValues?.get(1).orEmpty()
            return match.takeIf { it.isNotBlank() }?.replace('T', ' ')?.take(16) ?: "—"
        }

        fun allPrompts(): List<PromptDefinition> {
            val model = LlmSettingsLoader.loadSettingsModel(project)
            return PromptCategory.entries.flatMap { category ->
                mapForCategory(model, category).map { (name, content) ->
                    PromptDefinition(category, name, content, isGlobalPromptName(name), updatedText(name))
                }
            }
        }

        fun promptSuppressionKeys(prompt: PromptDefinition): List<String> =
            listOf(prompt.name, prompt.displayName)
                .filter { it.isNotBlank() }
                .distinct()
                .map { "${prompt.category.globalCategoryKey}:$it" }

        fun removePromptFromMap(map: MutableMap<String, String>, prompt: PromptDefinition) {
            val namesToRemove = setOf(prompt.name, prompt.displayName)
            map.keys
                .filter { it in namesToRemove || it.removePrefix("global/").replace(Regex("\\s*\\[[^]]+]$"), "") == prompt.displayName }
                .toList()
                .forEach { map.remove(it) }
        }

        fun applyPromptToDetails(prompt: PromptDefinition?) {
            selectedPrompt = prompt
            if (prompt == null) {
                titleLabel.text = "Select a prompt"
                titleLabel.foreground = fgColor
                scopeLabel.text = ""
                scopeLabel.toolTipText = null
                scopeLabel.foreground = mutedColor
                contentPreview.text = ""
                detailsPanel.removeAll()
                detailsPanel.add(JLabel("No prompt selected").apply {
                    foreground = mutedColor
                    font = promptFont
                }, GridBagConstraints().apply {
                    gridx = 0; gridy = 0; weightx = 1.0; weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                })
                detailsPanel.revalidate()
                detailsPanel.repaint()
                return
            }
            titleLabel.text = prompt.displayName
            titleLabel.foreground = categoryColor(prompt.category)
            scopeLabel.text = if (prompt.isGlobal) "🌐" else "📁"
            scopeLabel.toolTipText = if (prompt.isGlobal) "Global" else "Local"
            scopeLabel.foreground = scopeColor(prompt.isGlobal)
            contentPreview.text = prompt.content
            contentPreview.caretPosition = 0
            detailsPanel.removeAll()

            fun addDetailRow(row: Int, label: String, value: String, valueColor: Color = fgColor) {
                val labelConstraints = GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    weightx = 0.0
                    fill = GridBagConstraints.HORIZONTAL
                }
                detailsPanel.add(JLabel(label).apply {
                    foreground = mutedColor
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(designBorderColor),
                        BorderFactory.createEmptyBorder(10, 12, 10, 12)
                    )
                }, labelConstraints)

                val valueConstraints = GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                }
                detailsPanel.add(JLabel(value).apply {
                    foreground = valueColor
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(designBorderColor),
                        BorderFactory.createEmptyBorder(10, 12, 10, 12)
                    )
                }, valueConstraints)
            }

            addDetailRow(0, "Name", prompt.displayName, categoryColor(prompt.category))
            addDetailRow(1, "Type", "${categoryIcon(prompt.category)}  ${prompt.category.label}", categoryColor(prompt.category))
            addDetailRow(2, "Scope", if (prompt.isGlobal) "🌐" else "📁", scopeColor(prompt.isGlobal))
            addDetailRow(3, "Updated Time", prompt.updatedText)
            detailsPanel.revalidate()
            detailsPanel.repaint()
        }

        fun showEditor(prompt: PromptDefinition?) {
            categoryField.selectedItem = prompt?.category ?: PromptCategory.TEST
            categoryField.isEnabled = prompt?.isGlobal != true
            nameField.isEditable = prompt?.isGlobal != true
            nameField.text = when {
                prompt == null -> ""
                prompt.isGlobal -> prompt.name
                else -> prompt.displayName
            }
            contentField.text = prompt?.content.orEmpty()
            (viewCards.layout as CardLayout).show(viewCards, "edit")
            nameField.requestFocusInWindow()
        }

        fun refreshList(select: PromptDefinition? = selectedPrompt) {
            listModel.removeAllElements()
            val query = searchField.text.trim().lowercase()
            val selectedType = typeFilter.selectedItem?.toString().orEmpty()
            val sort = sortField.selectedItem as? PromptSort ?: PromptSort.TYPE
            val prompts = allPrompts()
                .filter { query.isBlank() || it.displayName.lowercase().contains(query) || it.content.lowercase().contains(query) }
                .filter { selectedType == "All Types" || it.category.label == selectedType }
                .let { list ->
                    when (sort) {
                        PromptSort.TYPE -> list.sortedWith(compareBy<PromptDefinition> { it.category.ordinal }.thenBy { it.displayName })
                        PromptSort.NAME -> list.sortedBy { it.displayName }
                        PromptSort.UPDATED -> list.sortedByDescending { it.updatedText }
                    }
                }
            listModel.addElement(PromptListRow.All(prompts.size))
            PromptCategory.entries.forEach { category ->
                val categoryPrompts = prompts.filter { it.category == category }
                listModel.addElement(PromptListRow.CategoryHeader(category, categoryPrompts.size, category in collapsedCategories))
                if (category !in collapsedCategories) {
                    categoryPrompts.forEach { listModel.addElement(PromptListRow.PromptRow(it)) }
                }
            }
            listModel.addElement(PromptListRow.CustomHeader)
            if (select != null) {
                val index = (0 until listModel.size()).firstOrNull {
                    val row = listModel.get(it)
                    row is PromptListRow.PromptRow && row.prompt.category == select.category && row.prompt.name == select.name
                } ?: -1
                if (index >= 0) {
                    promptList.selectedIndex = index
                } else {
                    promptList.clearSelection()
                }
            } else {
                promptList.clearSelection()
            }
            promptList.revalidate()
            promptList.repaint()
        }

        promptList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            when (val row = promptList.selectedValue) {
                is PromptListRow.All -> applyPromptToDetails(null)
                is PromptListRow.CategoryHeader -> {
                    if (row.category in collapsedCategories) collapsedCategories.remove(row.category) else collapsedCategories.add(row.category)
                    refreshList(null)
                }
                is PromptListRow.PromptRow -> {
                    applyPromptToDetails(row.prompt)
                    (viewCards.layout as CardLayout).show(viewCards, "view")
                }
                PromptListRow.CustomHeader, null -> Unit
            }
        }

        searchField.document.addDocumentListener(SimpleDocumentListener { refreshList(null) })
        typeFilter.addActionListener { refreshList(null) }
        sortField.addActionListener { refreshList(selectedPrompt) }

        fun compactActionButton(text: String, tooltip: String): JButton = JButton(text).apply {
            toolTipText = tooltip
            margin = Insets(1, 5, 1, 5)
            preferredSize = Dimension(28, 26)
            minimumSize = preferredSize
            font = commonFont.deriveFont(Font.PLAIN, 12f)
        }

        val saveButton = JButton("Save")
        val cancelButton = JButton("Cancel")
        val editButton = compactActionButton("✎", "Edit prompt")
        val duplicateButton = compactActionButton("⧉", "Duplicate prompt")
        val deleteButton = compactActionButton("🗑", "Delete or hide prompt")
        val refreshPromptsButton = compactActionButton("↻", "Refresh prompt list")
        val checkUpdateButton = JButton("☁ Check Update")
        val newPromptButton = JButton("+ New Prompt")
        val copyButton = JButton("⧉").apply {
            toolTipText = "Copy prompt content"
            margin = Insets(2, 4, 2, 4)
            font = promptFont.deriveFont(Font.PLAIN, promptFont.size - 1f)
            isFocusPainted = false
        }
        searchField.preferredSize = Dimension(searchField.preferredSize.width.coerceAtLeast(220), checkUpdateButton.preferredSize.height)
        searchField.minimumSize = Dimension(160, checkUpdateButton.preferredSize.height)

        fun promptUpdateMessage(status: LlmSettingsLoader.PromptUpdateStatus): String =
            "${status.message} Remote=${status.remoteCount}, LocalCache=${status.cachedCount}."

        fun setUpdateControlsEnabled(enabled: Boolean) {
            checkUpdateButton.isEnabled = enabled
        }

        fun handlePullUpdateStatus(status: LlmSettingsLoader.PromptUpdateStatus) {
            if (!status.configured) {
                Notifications.warn(project, "Prompt Manager", status.message)
                return
            }
            if (status.error) {
                Notifications.error(project, "Prompt Manager", status.message)
                return
            }
            refreshList(selectedPrompt)
            Notifications.info(project, "Prompt Manager", promptUpdateMessage(status))
        }

        fun performPullUpdate() {
            usage.record("context_box.prompt_manager.pull_update")
            setUpdateControlsEnabled(false)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Pull Prompt Updates", true) {
                private var status: LlmSettingsLoader.PromptUpdateStatus? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Pulling prompt updates..."
                    status = LlmSettingsLoader.pullBitbucketPromptUpdates(project)
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater {
                        setUpdateControlsEnabled(true)
                        status?.let { handlePullUpdateStatus(it) }
                    }
                }
            })
        }

        fun handleCheckUpdateStatus(status: LlmSettingsLoader.PromptUpdateStatus) {
            if (!status.configured) {
                Notifications.warn(project, "Prompt Manager", status.message)
                return
            }
            if (status.error) {
                Notifications.error(project, "Prompt Manager", status.message)
                return
            }
            if (status.hasUpdates) {
                performPullUpdate()
            } else {
                Notifications.info(project, "Prompt Manager", promptUpdateMessage(status))
            }
        }

        checkUpdateButton.addActionListener {
            usage.record("context_box.prompt_manager.check_update")
            setUpdateControlsEnabled(false)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Check Prompt Updates", true) {
                private var status: LlmSettingsLoader.PromptUpdateStatus? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Checking prompt updates..."
                    status = LlmSettingsLoader.checkBitbucketPromptUpdates(project)
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater {
                        setUpdateControlsEnabled(true)
                        status?.let { handleCheckUpdateStatus(it) }
                    }
                }
            })
        }

        refreshPromptsButton.addActionListener {
            usage.record("context_box.prompt_manager.refresh")
            refreshList(selectedPrompt)
        }

        newPromptButton.addActionListener {
            usage.record("context_box.prompt_manager.new")
            selectedPrompt = null
            showEditor(null)
        }

        editButton.addActionListener {
            usage.record("context_box.prompt_manager.edit")
            showEditor(selectedPrompt)
        }

        duplicateButton.addActionListener {
            usage.record("context_box.prompt_manager.duplicate")
            val prompt = selectedPrompt ?: return@addActionListener
            selectedPrompt = null
            categoryField.isEnabled = true
            nameField.isEditable = true
            categoryField.selectedItem = prompt.category
            nameField.text = "${prompt.displayName} Copy"
            contentField.text = prompt.content
            (viewCards.layout as CardLayout).show(viewCards, "edit")
        }

        copyButton.addActionListener {
            val content = selectedPrompt?.content.orEmpty()
            if (content.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(content))
                Notifications.info(project, "Prompt Manager", "Prompt content copied.")
            }
        }

        saveButton.addActionListener {
            usage.record("context_box.prompt_manager.save")
            val current = selectedPrompt
            val category = if (current?.isGlobal == true) current.category else categoryField.selectedItem as? PromptCategory ?: PromptCategory.TEST
            val name = if (current?.isGlobal == true) current.name else nameField.text.trim()
            val content = contentField.text.trim()
            if (name.isBlank() || content.isBlank()) {
                Messages.showErrorDialog(project, "Prompt name and content are required.", "Prompt Manager")
                return@addActionListener
            }
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val maps = mapsForModel(model).mapValues { LinkedHashMap(it.value) }
            if (current != null) {
                if (current.isGlobal && (current.category != category || current.name != name)) {
                    Messages.showErrorDialog(project, "Global prompt name/category cannot be changed.", "Prompt Manager")
                    return@addActionListener
                }
                maps.getValue(current.category).remove(current.name)
            }
            val target = maps.getValue(category)
            if (target.containsKey(name) && (current == null || current.category != category || current.name != name)) {
                Messages.showErrorDialog(project, "Prompt name already exists in selected category.", "Prompt Manager")
                return@addActionListener
            }
            target[name] = content
            val suppressionKey = "${category.globalCategoryKey}:$name"
            val suppressed = if (isGlobalPromptName(name)) model.suppressedGlobalPrompts - suppressionKey else model.suppressedGlobalPrompts
            val updated = updateModelFromMaps(model, maps, suppressed)
            LlmSettingsLoader.saveSettingsModel(project, updated)
            val saved = PromptDefinition(category, name, content, isGlobalPromptName(name), updatedText(name))
            applyPromptToDetails(saved)
            refreshList(saved)
            (viewCards.layout as CardLayout).show(viewCards, "view")
        }

        deleteButton.addActionListener {
            usage.record("context_box.prompt_manager.delete")
            val prompt = selectedPrompt
            if (prompt == null) {
                Messages.showErrorDialog(project, "Please select a prompt first.", "Prompt Manager")
                return@addActionListener
            }
            val actionDescription = if (prompt.isGlobal) "hide this global prompt locally" else "delete this local prompt"
            val confirm = Messages.showYesNoDialog(
                project,
                "Are you sure you want to $actionDescription?",
                "Prompt Manager",
                "Delete",
                "Cancel",
                null
            )
            if (confirm != Messages.YES) return@addActionListener
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val maps = mapsForModel(model).mapValues { LinkedHashMap(it.value) }
            removePromptFromMap(maps.getValue(prompt.category), prompt)
            val suppressed = if (prompt.isGlobal) {
                model.suppressedGlobalPrompts + promptSuppressionKeys(prompt)
            } else {
                model.suppressedGlobalPrompts
            }
            LlmSettingsLoader.saveSettingsModel(project, updateModelFromMaps(model, maps, suppressed))
            selectedPrompt = null
            refreshList(null)
            applyPromptToDetails(null)
            (viewCards.layout as CardLayout).show(viewCards, "view")
            Notifications.info(project, "Prompt Manager", if (prompt.isGlobal) "Global prompt hidden locally." else "Prompt deleted.")
        }

        cancelButton.addActionListener {
            categoryField.isEnabled = true
            nameField.isEditable = true
            (viewCards.layout as CardLayout).show(viewCards, "view")
        }

        val viewPanel = JPanel(BorderLayout(0, 12)).apply {
            background = pageColor
            add(JPanel(BorderLayout()).apply {
                background = surfaceColor
                add(JLabel("Details").apply {
                    foreground = fgColor
                    font = commonFont.deriveFont(16f)
                    border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
                }, BorderLayout.NORTH)
                add(JBScrollPane(detailsPanel).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                background = pageColor
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(6, 0, 8, 0)
                    add(JLabel("Prompt Content").apply { foreground = fgColor; font = commonFont.deriveFont(16f) })
                    add(copyButton)
                }, BorderLayout.NORTH)
                add(JBScrollPane(contentPreview).apply {
                    border = BorderFactory.createLineBorder(designBorderColor)
                    viewport.background = cardColor
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        val editPanel = JPanel(GridBagLayout()).apply {
            background = surfaceColor
            border = BorderFactory.createLineBorder(designBorderColor)
            val gbc = GridBagConstraints().apply {
                insets = Insets(6, 6, 6, 6)
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }
            fun addLabel(row: Int, text: String) {
                gbc.gridx = 0
                gbc.gridy = row
                gbc.weightx = 0.0
                add(JLabel(text).apply { foreground = mutedColor }, gbc)
            }
            addLabel(0, "Type")
            gbc.gridx = 1
            gbc.gridy = 0
            gbc.weightx = 1.0
            add(categoryField, gbc)
            addLabel(1, "Name")
            gbc.gridx = 1
            gbc.gridy = 1
            add(nameField, gbc)
            addLabel(2, "Content")
            gbc.gridx = 1
            gbc.gridy = 2
            gbc.fill = GridBagConstraints.BOTH
            gbc.weighty = 1.0
            add(JBScrollPane(contentField).apply { preferredSize = Dimension(480, 360) }, gbc)
            gbc.gridx = 1
            gbc.gridy = 3
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weighty = 0.0
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(saveButton)
                add(cancelButton)
            }, gbc)
        }
        viewCards.add(viewPanel, "view")
        viewCards.add(editPanel, "edit")

        val leftPanel = JPanel(BorderLayout(0, 8)).apply {
            background = pageColor
            preferredSize = Dimension(470, 600)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(JPanel(BorderLayout(8, 8)).apply {
                background = surfaceColor
                add(JLabel("Prompt Manager").apply { foreground = fgColor; font = commonFont.deriveFont(16f) }, BorderLayout.NORTH)
                add(searchField, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(checkUpdateButton)
                    add(newPromptButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(8, 8)).apply {
                background = pageColor
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(refreshPromptsButton)
                    add(typeFilter)
                    add(sortField)
                }, BorderLayout.NORTH)
                add(JBScrollPane(promptList).apply {
                    border = BorderFactory.createLineBorder(designBorderColor)
                    viewport.background = surfaceColor
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        val rightPanel = JPanel(BorderLayout(0, 12)).apply {
            background = pageColor
            border = BorderFactory.createEmptyBorder(18, 18, 18, 18)
            add(JPanel(BorderLayout(12, 0)).apply {
                background = pageColor
                add(JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                    add(titleLabel, BorderLayout.CENTER)
                    add(scopeLabel, BorderLayout.EAST)
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                    add(editButton)
                    add(duplicateButton)
                    add(deleteButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(viewCards, BorderLayout.CENTER)
        }

        refreshList(null)
        applyPromptToDetails(null)
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel).apply {
            resizeWeight = 0.38
            dividerSize = 1
            border = BorderFactory.createEmptyBorder()
            background = pageColor
        }
    }

    private enum class SkillSort(val label: String) {
        NAME("Sort by: Name"),
        UPDATED("Sort by: Updated");

        override fun toString(): String = label
    }

    private data class SkillDefinition(
        val name: String,
        val content: String,
        val isGlobal: Boolean,
        val updatedText: String = "—"
    ) {
        val displayName: String get() = name.removePrefix("global/").replace(Regex("\\s*\\[[^]]+]$"), "")
    }

    private sealed class SkillListRow {
        data class All(val count: Int) : SkillListRow()
        data class SkillRow(val skill: SkillDefinition) : SkillListRow()
    }

    private fun createSkillManagerPanel(
        project: Project,
        bgColor: Color,
        fgColor: Color,
        borderColor: Color,
        commonFont: Font
    ): Component {
        val usage = ButtonUsageReportService.getInstance(project)
        val pageColor = ThemeColors.pageBg
        val surfaceColor = ThemeColors.surfaceBg
        val inputColor = ThemeColors.inputBg
        val accentColor = ThemeColors.accentBlue
        val mutedColor = ThemeColors.mutedFg
        val cardColor = ThemeColors.cardBg
        val designBorderColor = borderColor
        val skillFont = UIManager.getFont("EditorPane.font")?.deriveFont(Font.PLAIN, 14f) ?: Font("JetBrains Mono", Font.PLAIN, 14)
        val listModel = DefaultListModel<SkillListRow>()
        var selectedSkill: SkillDefinition? = null

        val searchField = JTextField().apply {
            toolTipText = "Search skills"
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(designBorderColor),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
        }
        val sortField = JComboBox(SkillSort.entries.toTypedArray()).apply {
            background = inputColor
            foreground = fgColor
        }
        val skillList = JList(listModel).apply {
            font = commonFont
            background = surfaceColor
            foreground = fgColor
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectionBackground = accentColor
            selectionForeground = Color.WHITE
            fixedCellHeight = -1
            cellRenderer = SkillListCellRenderer(surfaceColor, fgColor, mutedColor, designBorderColor, accentColor, commonFont)
        }
        val nameField = JTextField().apply {
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(designBorderColor),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
        }
        val contentField = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            font = skillFont
            background = inputColor
            foreground = fgColor
            caretColor = fgColor
        }
        val titleLabel = JLabel("Select a skill").apply {
            foreground = fgColor
            font = commonFont.deriveFont(Font.PLAIN, 18f)
        }
        val scopeLabel = JLabel("—").apply { foreground = mutedColor }
        val contentPreview = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = skillFont
            background = cardColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        val viewCards = JPanel(CardLayout()).apply { background = pageColor }

        fun allSkills(): List<SkillDefinition> {
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val suppressed = model.suppressedGlobalSkills.toSet()
            val items = Yaml().load<Any?>(model.skillProfilesYaml) as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val yamlSkills = items.mapNotNull { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString().orEmpty()
                if (key.isBlank() || value.isBlank() || key in suppressed) null
                else SkillDefinition(key, value, isGlobalSkillName(key), updatedText(key))
            }
            val yamlDisplayNames = yamlSkills.map { it.displayName }.toSet()
            val localSkills = LlmSettingsLoader.loadLocalSkillFiles()
                .filter { (name, _) -> name !in yamlDisplayNames && !isGlobalSkillName(name) && name !in suppressed }
                .map { (name, content) ->
                    SkillDefinition(name, content, false, "—")
                }
            return localSkills + yamlSkills
        }

        fun updatedText(name: String): String {
            val match = Regex("\\[([^]]+)]").find(name)
            return match?.groupValues?.get(1) ?: "—"
        }

        fun showSkillEditor(skill: SkillDefinition?) {
            nameField.isEditable = skill?.isGlobal != true
            nameField.text = when {
                skill == null -> ""
                skill.isGlobal -> skill.name
                else -> skill.displayName
            }
            contentField.text = skill?.content.orEmpty()
            (viewCards.layout as CardLayout).show(viewCards, "edit")
            nameField.requestFocusInWindow()
        }

        fun applySkillToDetails(skill: SkillDefinition?) {
            selectedSkill = skill
            if (skill == null) {
                titleLabel.text = "Select a skill"
                titleLabel.foreground = fgColor
                scopeLabel.text = ""
                scopeLabel.foreground = mutedColor
                contentPreview.text = ""
                return
            }
            titleLabel.text = skill.displayName
            titleLabel.foreground = fgColor
            scopeLabel.text = if (skill.isGlobal) "🌐" else "📁"
            scopeLabel.foreground = if (skill.isGlobal) Color(0x60, 0xA5, 0xFA) else Color(0x34, 0xD3, 0x99)
            contentPreview.text = skill.content
            contentPreview.caretPosition = 0
        }

        fun refreshList(select: SkillDefinition? = selectedSkill) {
            listModel.removeAllElements()
            val query = searchField.text.trim().lowercase()
            val sort = sortField.selectedItem as? SkillSort ?: SkillSort.NAME
            val skills = allSkills()
                .filter { query.isBlank() || it.displayName.lowercase().contains(query) || it.content.lowercase().contains(query) }
                .let { list ->
                    when (sort) {
                        SkillSort.NAME -> list.sortedBy { it.displayName.lowercase() }
                        SkillSort.UPDATED -> list.sortedByDescending { it.updatedText }
                    }
                }
            listModel.addElement(SkillListRow.All(skills.size))
            skills.forEach { listModel.addElement(SkillListRow.SkillRow(it)) }
            if (select != null) {
                for (i in 0 until listModel.size()) {
                    val row = listModel[i]
                    if (row is SkillListRow.SkillRow && row.skill.name == select.name) {
                        skillList.selectedIndex = i
                        skillList.ensureIndexIsVisible(i)
                        break
                    }
                }
            }
        }

        skillList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val row = skillList.selectedValue as? SkillListRow ?: return@addListSelectionListener
            when (row) {
                is SkillListRow.All -> applySkillToDetails(null)
                is SkillListRow.SkillRow -> applySkillToDetails(row.skill)
            }
        }

        searchField.document.addDocumentListener(SimpleDocumentListener { refreshList(null) })
        sortField.addActionListener { refreshList(selectedSkill) }

        fun compactActionButton(text: String, tooltip: String): JButton = JButton(text).apply {
            toolTipText = tooltip
            margin = Insets(1, 5, 1, 5)
            preferredSize = Dimension(28, 26)
            minimumSize = preferredSize
            font = commonFont.deriveFont(Font.PLAIN, 12f)
        }

        val saveButton = JButton("Save")
        val cancelButton = JButton("Cancel")
        val editButton = compactActionButton("✎", "Edit skill")
        val duplicateButton = compactActionButton("⧉", "Duplicate skill")
        val deleteButton = compactActionButton("🗑", "Delete or hide skill")
        val refreshSkillsButton = compactActionButton("↻", "Refresh skill list")
        val checkUpdateButton = JButton("☁ Check Update")
        val newSkillButton = JButton("+ New Skill")
        val copyButton = JButton("⧉").apply {
            toolTipText = "Copy skill content"
            margin = Insets(2, 4, 2, 4)
            font = skillFont.deriveFont(Font.PLAIN, skillFont.size - 1f)
            isFocusPainted = false
        }

        searchField.preferredSize = Dimension(searchField.preferredSize.width.coerceAtLeast(220), checkUpdateButton.preferredSize.height)
        searchField.minimumSize = Dimension(160, checkUpdateButton.preferredSize.height)

        fun setUpdateControlsEnabled(enabled: Boolean) {
            checkUpdateButton.isEnabled = enabled
        }

        fun performPullSkillUpdate() {
            usage.record("context_box.skill_manager.pull_update")
            setUpdateControlsEnabled(false)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Pull Skill Updates", true) {
                private var status: LlmSettingsLoader.PromptUpdateStatus? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Pulling skill updates..."
                    status = LlmSettingsLoader.pullBitbucketSkillUpdates(project)
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater {
                        setUpdateControlsEnabled(true)
                        val s = status ?: return@invokeLater
                        if (!s.configured) {
                            Notifications.warn(project, "Skill Manager", s.message)
                            return@invokeLater
                        }
                        if (s.error) {
                            Notifications.error(project, "Skill Manager", s.message)
                            return@invokeLater
                        }
                        refreshList(selectedSkill)
                        Notifications.info(project, "Skill Manager", s.message)
                    }
                }
            })
        }

        fun handleCheckSkillUpdateStatus(status: LlmSettingsLoader.PromptUpdateStatus) {
            if (!status.configured) {
                Notifications.warn(project, "Skill Manager", status.message)
                return
            }
            if (status.error) {
                Notifications.error(project, "Skill Manager", status.message)
                return
            }
            if (status.hasUpdates) {
                performPullSkillUpdate()
            } else {
                Notifications.info(project, "Skill Manager", status.message)
            }
        }

        checkUpdateButton.addActionListener {
            usage.record("context_box.skill_manager.check_update")
            setUpdateControlsEnabled(false)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Check Skill Updates", true) {
                private var status: LlmSettingsLoader.PromptUpdateStatus? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Checking skill updates..."
                    status = LlmSettingsLoader.checkBitbucketSkillUpdates(project)
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater {
                        setUpdateControlsEnabled(true)
                        status?.let { handleCheckSkillUpdateStatus(it) }
                    }
                }
            })
        }

        refreshSkillsButton.addActionListener {
            usage.record("context_box.skill_manager.refresh")
            refreshList(selectedSkill)
        }

        newSkillButton.addActionListener {
            usage.record("context_box.skill_manager.new")
            selectedSkill = null
            showSkillEditor(null)
        }

        editButton.addActionListener {
            usage.record("context_box.skill_manager.edit")
            showSkillEditor(selectedSkill)
        }

        duplicateButton.addActionListener {
            usage.record("context_box.skill_manager.duplicate")
            val skill = selectedSkill ?: return@addActionListener
            selectedSkill = null
            nameField.isEditable = true
            nameField.text = "${skill.displayName} Copy"
            contentField.text = skill.content
            (viewCards.layout as CardLayout).show(viewCards, "edit")
        }

        copyButton.addActionListener {
            val content = selectedSkill?.content.orEmpty()
            if (content.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(content))
                Notifications.info(project, "Skill Manager", "Skill content copied.")
            }
        }

        fun dumpYaml(value: Map<String, String>): String {
            val options = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                indent = 2
                isPrettyFlow = true
            }
            return Yaml(options).dump(value).trimEnd()
        }

        saveButton.addActionListener {
            usage.record("context_box.skill_manager.save")
            val current = selectedSkill
            val name = if (current?.isGlobal == true) current.name else nameField.text.trim()
            val content = contentField.text.trim()
            if (name.isBlank() || content.isBlank()) {
                Messages.showErrorDialog(project, "Skill name and content are required.", "Skill Manager")
                return@addActionListener
            }
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val items = parseSkillItems(model.skillProfilesYaml)
            if (current != null) {
                if (current.isGlobal && current.name != name) {
                    Messages.showErrorDialog(project, "Global skill name cannot be changed.", "Skill Manager")
                    return@addActionListener
                }
                items.remove(current.name)
            }
            if (items.containsKey(name) && (current == null || current.name != name)) {
                Messages.showErrorDialog(project, "Skill name already exists.", "Skill Manager")
                return@addActionListener
            }
            items[name] = content
            val suppressed = if (isGlobalSkillName(name)) model.suppressedGlobalSkills.filter { it != name } else model.suppressedGlobalSkills
            val updated = model.copy(
                skillProfilesYaml = dumpYaml(items),
                suppressedGlobalSkills = suppressed.distinct().sorted()
            )
            LlmSettingsLoader.saveSettingsModel(project, updated)
            val saved = SkillDefinition(name, content, isGlobalSkillName(name), updatedText(name))
            applySkillToDetails(saved)
            refreshList(saved)
            (viewCards.layout as CardLayout).show(viewCards, "view")
        }

        deleteButton.addActionListener {
            usage.record("context_box.skill_manager.delete")
            val skill = selectedSkill
            if (skill == null) {
                Messages.showErrorDialog(project, "Please select a skill first.", "Skill Manager")
                return@addActionListener
            }
            val actionDescription = if (skill.isGlobal) "hide this global skill locally" else "delete this local skill"
            val confirm = Messages.showYesNoDialog(project,
                "Are you sure you want to $actionDescription?", "Skill Manager", "Delete", "Cancel", null)
            if (confirm != Messages.YES) return@addActionListener
            val model = LlmSettingsLoader.loadSettingsModel(project)
            val items = parseSkillItems(model.skillProfilesYaml)
            val inYaml = items.containsKey(skill.name)
            items.remove(skill.name)
            val suppressed = if (skill.isGlobal) {
                model.suppressedGlobalSkills + skill.name
            } else if (!inYaml) {
                val safeName = skill.displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                File(LlmSettingsLoader.skillsDir(), "$safeName.md").delete()
                model.suppressedGlobalSkills
            } else {
                model.suppressedGlobalSkills
            }
            val updated = model.copy(
                skillProfilesYaml = dumpYaml(items),
                suppressedGlobalSkills = suppressed.distinct().sorted()
            )
            LlmSettingsLoader.saveSettingsModel(project, updated)
            selectedSkill = null
            refreshList(null)
            applySkillToDetails(null)
            (viewCards.layout as CardLayout).show(viewCards, "view")
            Notifications.info(project, "Skill Manager", if (skill.isGlobal) "Global skill hidden locally." else "Skill deleted.")
        }

        cancelButton.addActionListener {
            nameField.isEditable = true
            (viewCards.layout as CardLayout).show(viewCards, "view")
        }

        val viewPanel = JPanel(BorderLayout(0, 12)).apply {
            background = pageColor
            add(JPanel(BorderLayout()).apply {
                background = pageColor
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(6, 0, 8, 0)
                    add(JLabel("Skill Content").apply { foreground = fgColor; font = commonFont.deriveFont(16f) })
                    add(copyButton)
                }, BorderLayout.NORTH)
                add(JBScrollPane(contentPreview).apply {
                    border = BorderFactory.createLineBorder(designBorderColor)
                    viewport.background = cardColor
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        val editPanel = JPanel(GridBagLayout()).apply {
            background = surfaceColor
            border = BorderFactory.createLineBorder(designBorderColor)
            val gbc = GridBagConstraints().apply {
                insets = Insets(6, 6, 6, 6)
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }
            fun addLabel(row: Int, text: String) {
                gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
                add(JLabel(text).apply { foreground = mutedColor }, gbc)
            }
            addLabel(0, "Name")
            gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
            add(nameField, gbc)
            addLabel(1, "Content")
            gbc.gridx = 1; gbc.gridy = 1
            gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0
            add(JBScrollPane(contentField).apply { preferredSize = Dimension(480, 360) }, gbc)
            gbc.gridx = 1; gbc.gridy = 2
            gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0.0
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(saveButton)
                add(cancelButton)
            }, gbc)
        }
        viewCards.add(viewPanel, "view")
        viewCards.add(editPanel, "edit")

        val leftPanel = JPanel(BorderLayout(0, 8)).apply {
            background = pageColor
            preferredSize = Dimension(470, 600)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(JPanel(BorderLayout(8, 8)).apply {
                background = surfaceColor
                add(JLabel("Skill Manager").apply { foreground = fgColor; font = commonFont.deriveFont(16f) }, BorderLayout.NORTH)
                add(searchField, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(checkUpdateButton)
                    add(newSkillButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(8, 8)).apply {
                background = pageColor
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(refreshSkillsButton)
                    add(sortField)
                }, BorderLayout.NORTH)
                add(JBScrollPane(skillList).apply {
                    border = BorderFactory.createLineBorder(designBorderColor)
                    viewport.background = surfaceColor
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        val rightPanel = JPanel(BorderLayout(0, 12)).apply {
            background = pageColor
            border = BorderFactory.createEmptyBorder(18, 18, 18, 18)
            add(JPanel(BorderLayout(12, 0)).apply {
                background = pageColor
                add(JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                    add(titleLabel, BorderLayout.CENTER)
                    add(scopeLabel, BorderLayout.EAST)
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                    add(editButton)
                    add(duplicateButton)
                    add(deleteButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(viewCards, BorderLayout.CENTER)
        }

        refreshList(null)
        applySkillToDetails(null)
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel).apply {
            resizeWeight = 0.38
            dividerSize = 1
            border = BorderFactory.createEmptyBorder()
            background = pageColor
        }
    }

    private inner class SkillListCellRenderer(
        private val bgColor: Color,
        private val fgColor: Color,
        private val mutedColor: Color,
        private val borderColor: Color,
        private val accentColor: Color,
        private val commonFont: Font
    ) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(8, 0)).apply {
                background = if (isSelected) accentColor else bgColor
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)
                )
            }
            val textColor = if (isSelected) Color.WHITE else fgColor
            when (val row = value as? SkillListRow) {
                is SkillListRow.All -> {
                    panel.add(JLabel("☁  All").apply {
                        foreground = textColor
                        font = commonFont.deriveFont(Font.BOLD, 14f)
                    }, BorderLayout.WEST)
                    panel.add(JLabel("${row.count}").apply {
                        foreground = mutedColor
                        font = commonFont.deriveFont(Font.PLAIN, 12f)
                    }, BorderLayout.EAST)
                }
                is SkillListRow.SkillRow -> {
                    val icon = if (row.skill.isGlobal) "🌐" else "📁"
                    val nameLabel = JLabel("${icon}  ${row.skill.displayName}").apply {
                        foreground = textColor
                        font = commonFont.deriveFont(Font.PLAIN, 14f)
                    }
                    val updatedLabel = JLabel(row.skill.updatedText).apply {
                        foreground = mutedColor
                        font = commonFont.deriveFont(Font.PLAIN, 11f)
                    }
                    val leftPanel = JPanel(BorderLayout(0, 2)).apply {
                        isOpaque = false
                        add(nameLabel, BorderLayout.NORTH)
                        add(updatedLabel, BorderLayout.SOUTH)
                    }
                    panel.add(leftPanel, BorderLayout.CENTER)
                }
                null -> {}
            }
            return panel
        }
    }

    private fun parseSkillItems(yaml: String): LinkedHashMap<String, String> {
        val parsed = Yaml().load<Any?>(yaml) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val result = LinkedHashMap<String, String>()
        parsed.forEach { (k, v) ->
            val key = k?.toString()?.trim().orEmpty()
            val value = v?.toString().orEmpty()
            if (key.isNotBlank() && value.isNotBlank()) result[key] = value
        }
        return result
    }

    private fun isGlobalSkillName(name: String): Boolean {
        return name.startsWith("[ADA]") || name.startsWith("[repo]") || name.startsWith("global/")
    }

    private fun updatedText(name: String): String {
        val match = Regex("\\[([^]]+)]").find(name)
        return match?.groupValues?.get(1) ?: "—"
    }

    private inner class PromptListCellRenderer(
        private val bgColor: Color,
        private val fgColor: Color,
        private val mutedColor: Color,
        private val borderColor: Color,
        private val accentColor: Color,
        private val commonFont: Font
    ) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(8, 0)).apply {
                background = if (isSelected) accentColor else bgColor
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)
                )
            }
            fun label(text: String, color: Color = fgColor, size: Float = 13f): JLabel = JLabel(text).apply {
                foreground = if (isSelected) Color.WHITE else color
                font = commonFont.deriveFont(size)
            }
            when (value) {
                is PromptListRow.All -> {
                    panel.add(label("☁  All", fgColor, 15f), BorderLayout.WEST)
                    panel.add(label(value.count.toString(), mutedColor), BorderLayout.EAST)
                }
                is PromptListRow.CategoryHeader -> {
                    val arrow = if (value.collapsed) "›" else "⌃"
                    panel.add(label("${categoryIcon(value.category)}  ${value.category.label}", categoryColor(value.category), 15f), BorderLayout.WEST)
                    panel.add(label("${value.count}   $arrow", mutedColor), BorderLayout.EAST)
                }
                is PromptListRow.PromptRow -> {
                    val prompt = value.prompt
                    panel.border = BorderFactory.createEmptyBorder(6, 28, 6, 8)
                    panel.add(label("${categoryIcon(prompt.category)}  ${prompt.displayName}", categoryColor(prompt.category)), BorderLayout.CENTER)
                    panel.add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        background = if (isSelected) accentColor else bgColor
                        add(label(if (prompt.isGlobal) "🌐" else "📁", scopeColor(prompt.isGlobal), 12f))
                        if (prompt.updatedText != "—") {
                            add(label(prompt.updatedText, mutedColor, 12f))
                        }
                    }, BorderLayout.EAST)
                }
                PromptListRow.CustomHeader -> {
                    panel.add(label("⚙  Custom", fgColor, 15f), BorderLayout.WEST)
                    panel.add(label("0   ›", mutedColor), BorderLayout.EAST)
                }
                else -> panel.add(label(""), BorderLayout.WEST)
            }
            return panel
        }
    }

    private fun interface SimpleDocumentListener : javax.swing.event.DocumentListener {
        fun update()
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = update()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = update()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = update()
    }

    private fun createLogPanel(
        bgColor: Color,
        fgColor: Color,
        borderColor: Color,
        commonFont: Font
    ): JPanel {
        val logArea = JTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = commonFont
            background = bgColor
            foreground = fgColor
            caretColor = fgColor
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }

        fun render() {
            logArea.text = RuntimeLogStore.snapshot().joinToString("\n")
            logArea.caretPosition = logArea.document.length
        }

        val refreshButton = JButton("Refresh").apply {
            addActionListener { render() }
        }
        val clearButton = JButton("Clear").apply {
            addActionListener {
                RuntimeLogStore.clear()
                render()
            }
        }

        render()

        return JPanel(BorderLayout()).apply {
            background = bgColor
            foreground = fgColor
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 6)).apply {
                isOpaque = false
                add(refreshButton)
                add(clearButton)
            }, BorderLayout.NORTH)
            add(JBScrollPane(logArea).apply {
                viewport.background = bgColor
                background = bgColor
                border = BorderFactory.createLineBorder(borderColor)
            }, BorderLayout.CENTER)
        }
    }

    private fun createReadmePanel(
        project: Project,
        bgColor: Color,
        fgColor: Color,
        borderColor: Color,
        commonFont: Font
    ): JPanel {
        val pageColor = ThemeColors.pageBg
        val surfaceColor = ThemeColors.surfaceBg
        val cardColor = ThemeColors.cardBg
        val mutedColor = ThemeColors.mutedFg
        val accentColor = ThemeColors.accentBlue
        val bodyFont = commonFont.deriveFont(Font.PLAIN, 13f)
        val smallFont = commonFont.deriveFont(Font.PLAIN, 12f)
        val sectionFont = commonFont.deriveFont(Font.BOLD, 17f)
        val titleFont = commonFont.deriveFont(Font.BOLD, 22f)

        data class GuideFeature(
            val icon: String,
            val title: String,
            val summary: String,
            val category: String,
            val intro: String,
            val steps: List<Pair<String, String>>,
            val configurable: List<String>,
            val bestFor: List<String>,
            val tip: String,
            val actionLabel: String,
            val action: () -> Unit
        )

        lateinit var contentRoot: JPanel

        fun selectTab(tabName: String) {
            val tabs = javax.swing.SwingUtilities.getAncestorOfClass(JTabbedPane::class.java, contentRoot)
            if (tabs is JTabbedPane) {
                for (index in 0 until tabs.tabCount) {
                    if (tabs.getTitleAt(index) == tabName) {
                        tabs.selectedIndex = index
                        return
                    }
                }
            }
        }

        fun showUsage(title: String, message: String) {
            Notifications.info(project, title, message)
        }

        fun featureButton(text: String, action: () -> Unit) = JButton(text).apply {
            font = bodyFont.deriveFont(Font.BOLD)
            foreground = accentColor
            background = cardColor
            border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }

        val features = listOf(
            GuideFeature(
                icon = "⚗", title = "Generate Tests", category = "Test Generation",
                summary = "Generate unit or API tests from Java code or OpenAPI contracts.",
                intro = "Generate comprehensive JUnit 5 / Rest Assured or Karate tests from Java source files and OpenAPI contracts.",
                steps = listOf(
                    "Open a supported file" to "Open a Java source file or an OpenAPI .yaml/.yml contract in the editor.",
                    "Click Generate Tests By AI" to "Use the action in the editor notification bar shown above the file.",
                    "Configure generation options" to "Choose framework, prompt profile, output path, class name, package, base URL and extra notes.",
                    "Review the generated file" to "The generated test is written to the selected path and its diff is added to Context Box."
                ),
                configurable = listOf("Framework: JUnit 5 / Rest Assured or Karate", "Prompt profile", "Output location", "Class and package name", "Base URL and extra notes"),
                bestFor = listOf("Java unit and API tests", "OpenAPI contract testing", "Boundary and edge-case coverage"),
                tip = "External method signatures called by the Java target are collected to help the LLM produce more accurate mocks.",
                actionLabel = "Show me how",
                action = { showUsage("Generate Tests", "Open a Java source or OpenAPI .yaml/.yml file, then click 'Generate Tests By AI' in the editor notification bar.") }
            ),
            GuideFeature(
                icon = "✎", title = "Commit Message", category = "Commit Generation",
                summary = "Generate a meaningful commit message from your current changes.",
                intro = "Use an AI-generated commit message based on your staged and unstaged Git diff.",
                steps = listOf(
                    "Open the Commit window" to "Open the IDE VCS Commit tool window.",
                    "Choose Generate Commit Message" to "Use the commit toolbar action and select a configured prompt profile.",
                    "Review the generated message" to "The generated text is inserted into the Commit Message field for editing before commit."
                ),
                configurable = listOf("Commit prompt profile", "Prompt templates in Prompt Manager", "Optional JIRA-style branch prefix"),
                bestFor = listOf("Consistent commit conventions", "Summarizing multi-file changes", "Reducing repetitive writing"),
                tip = "If the branch name contains a JIRA-style key such as ABC-123, it is used as a commit-message prefix.",
                actionLabel = "Open Prompt Manager",
                action = { selectTab(PROMPT_MANAGER_TAB) }
            ),
            GuideFeature(
                icon = "⑂", title = "Branch Analysis", category = "Branch Compare",
                summary = "Analyze differences between branches or commits with AI insights.",
                intro = "Analyze a Git branch or commit comparison with a selectable branch-diff prompt profile.",
                steps = listOf(
                    "Open Git Log" to "Open VCS → Log in the IDE.",
                    "Select a target" to "Right-click the branch or commit that you want to compare with the current branch.",
                    "Choose Analyze Branch Diff" to "Select a configured prompt profile from the context menu.",
                    "Review in Context Box" to "The AI branch summary appears in the Context tab and can be used to create a PR."
                ),
                configurable = listOf("Branch-diff prompt profile", "Target branch or selected commit", "Follow-up questions in Context Box"),
                bestFor = listOf("Pull-request preparation", "Risk review", "Understanding unfamiliar changes"),
                tip = "Branch analysis uses the current branch as the source and the Git Log selection as the comparison target.",
                actionLabel = "Open Context",
                action = { selectTab(CONTEXT_TAB) }
            ),
            GuideFeature(
                icon = "⇧", title = "Push & Create PR", category = "Pull Request",
                summary = "Push your current branch and create a pull request with an AI summary.",
                intro = "Push the checked-out Git branch and optionally create a Bitbucket or GitHub pull request with an AI-generated title and description.",
                steps = listOf(
                    "Open the Commit window" to "Use the VCS Commit tool window after preparing your branch.",
                    "Choose Push and Create PR" to "Open the push dialog from the commit toolbar action.",
                    "Select a target branch" to "Keep or change the target branch and choose whether to create a PR after push.",
                    "Open the created PR" to "The IDE notification shows the pull-request URL after creation."
                ),
                configurable = listOf("Create PR after push", "Target branch", "PR prompt template", "Git remote credentials"),
                bestFor = listOf("Fast PR creation", "Consistent PR summaries", "Bitbucket and GitHub repositories"),
                tip = "A supported Bitbucket or GitHub remote and valid credentials are required for automatic pull-request creation.",
                actionLabel = "Show me how",
                action = { showUsage("Push & Create PR", "Open the VCS Commit window and choose 'Push and Create PR' from its toolbar.") }
            ),
            GuideFeature(
                icon = "</>", title = "Code Review", category = "Code Review",
                summary = "Review selected code and get AI suggestions and improvements.",
                intro = "Review selected editor code using a Code Review prompt from Prompt Manager.",
                steps = listOf(
                    "Select code" to "Highlight the code you want to review in the editor.",
                    "Open Code Generate & Review" to "Right-click the selection and choose the editor context-menu action.",
                    "Choose a Code Review prompt" to "Select a review profile and optionally add extra requirements.",
                    "Read the response" to "The LLM review is displayed in the Context tab."
                ),
                configurable = listOf("Code Review prompt profile", "Extra requirements", "Reusable prompt templates"),
                bestFor = listOf("Focused code review", "Maintainability feedback", "Security and performance checks"),
                tip = "Code Review and Code Generate share the same editor context-menu action; choose the appropriate prompt category in the dialog.",
                actionLabel = "Open Prompt Manager",
                action = { selectTab(PROMPT_MANAGER_TAB) }
            ),
            GuideFeature(
                icon = "✣", title = "Code Generate", category = "Code Generation",
                summary = "Generate code for the selected context and your requirements.",
                intro = "Generate code from the selected editor context using a Code Generate prompt and optional extra requirements.",
                steps = listOf(
                    "Select context code" to "Highlight relevant code in the editor.",
                    "Open Code Generate & Review" to "Right-click the selection and choose the editor context-menu action.",
                    "Choose a Code Generate prompt" to "Select a generation profile and describe the desired change.",
                    "Review the response" to "The generated suggestion is displayed in the Context tab."
                ),
                configurable = listOf("Code Generate prompt profile", "Extra requirements", "Reusable prompt templates"),
                bestFor = listOf("Boilerplate generation", "Small focused enhancements", "Context-aware implementation ideas"),
                tip = "Provide a focused selection and explicit requirements to keep generated changes relevant.",
                actionLabel = "Open Prompt Manager",
                action = { selectTab(PROMPT_MANAGER_TAB) }
            ),
            GuideFeature(
                icon = "♢", title = "SonarQube Coverage", category = "Coverage Analysis",
                summary = "Inspect coverage, scan issues and generate missing tests.",
                intro = "Fetch SonarQube coverage or run local inspections, then optionally generate missing tests with AI.",
                steps = listOf(
                    "Open SonarQube Coverage" to "Choose Tools → SonarQube Coverage.",
                    "Configure the scan" to "Enter server credentials for online mode or enable local / mock scan mode.",
                    "Review metrics and issues" to "Inspect coverage cards, file coverage and local inspection findings in Sonar Cube.",
                    "Generate missing tests" to "Optionally ask AI to generate tests for files below the target coverage."
                ),
                configurable = listOf("Server URL and project key", "Token or username/password", "Target coverage and max files", "Local, mock or online scan mode"),
                bestFor = listOf("Coverage gap analysis", "Local code-quality inspections", "Generating missing tests"),
                tip = "Local scan mode detects TODO/FIXME markers, printStackTrace calls, empty catches and hard-coded secrets without a SonarQube server.",
                actionLabel = "Open Sonar Cube",
                action = { selectTab(SONAR_CUBE_TAB) }
            ),
            GuideFeature(
                icon = "☵", title = "AI Chat (Context Box)", category = "Context Box",
                summary = "Chat with AI using recent project-task context and history.",
                intro = "Ask follow-up questions in a multi-turn chat that includes recent Context Box history.",
                steps = listOf(
                    "Open Context" to "Switch to the Context tab in AI Context Box.",
                    "Type a message" to "Ask a follow-up question, request a translation or refine a generated test.",
                    "Send with Enter" to "The recent conversation history is sent to the configured LLM.",
                    "Apply regenerated tests" to "When test code is returned, use Generate Tests → to overwrite the target file."
                ),
                configurable = listOf("LLM provider and model", "API key or login flow", "Recent conversation context"),
                bestFor = listOf("Follow-up questions", "Refining generated tests", "Explaining branch analysis"),
                tip = "Context Box retains the most recent messages so follow-up requests can build on earlier generated output.",
                actionLabel = "Open Context",
                action = { selectTab(CONTEXT_TAB) }
            )
        )

        val model = LlmSettingsLoader.loadSettingsModel(project)
        val repoConfigured = model.bitbucketPromptRepoEnabled && model.bitbucketPromptRepoUrl.isNotBlank()
        val llmConfigured = model.llmModel.isNotBlank() && (
            (model.llmProvider == "openai-compatible" && model.llmEndpoint.isNotBlank() &&
                (model.llmApiKey.isNotBlank() || model.llmApiKeyEnv.isNotBlank() || model.loginEnabled)) ||
                (model.llmProvider == "template" && model.llmTemplateEnabled && model.llmTemplateUrl.isNotBlank())
            )
        val setupCount = listOf(repoConfigured, llmConfigured).count { it }

        fun panelBorder() = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor),
            BorderFactory.createEmptyBorder(14, 16, 14, 16)
        )

        fun label(text: String, font: Font = bodyFont, color: Color = fgColor) = JLabel(text).apply {
            this.font = font
            foreground = color
        }

        fun html(text: String, width: Int, font: Font = bodyFont, color: Color = fgColor) = JLabel(
            "<html><body style='width:${width}px'>${text}</body></html>"
        ).apply {
            this.font = font
            foreground = color
        }

        contentRoot = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = pageColor
            border = BorderFactory.createEmptyBorder(18, 18, 18, 18)
        }

        val detailsContainer = JPanel(BorderLayout()).apply {
            background = pageColor
            alignmentX = Component.LEFT_ALIGNMENT
        }

        lateinit var showFeature: (GuideFeature) -> Unit
        val cardPanels = mutableListOf<JPanel>()

        fun createCard(feature: GuideFeature): JPanel {
            val card = JPanel(BorderLayout(0, 8)).apply {
                background = cardColor
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor),
                    BorderFactory.createEmptyBorder(14, 14, 12, 14)
                )
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            card.add(label(feature.icon, commonFont.deriveFont(Font.BOLD, 22f), accentColor), BorderLayout.NORTH)
            card.add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(label(feature.title, commonFont.deriveFont(Font.BOLD, 14f)))
                add(Box.createVerticalStrut(6))
                add(html(feature.summary, 176, smallFont, mutedColor))
            }, BorderLayout.CENTER)
            card.add(featureButton("Learn more  →") { showFeature(feature) }, BorderLayout.SOUTH)
            val listener = object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) = showFeature(feature)
            }
            card.addMouseListener(listener)
            card.preferredSize = Dimension(218, 168)
            card.minimumSize = Dimension(218, 168)
            card.maximumSize = Dimension(218, 168)
            cardPanels += card
            return card
        }

        val welcomePanel = JPanel(BorderLayout(16, 0)).apply {
            background = surfaceColor
            border = panelBorder()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 184)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(label("Welcome to CQA 👋", titleFont))
                add(Box.createVerticalStrut(6))
                add(label("Complete this workflow before using AI features:", bodyFont, mutedColor))
                add(Box.createVerticalStrut(10))
                add(html("<b><font color='#3B82F6'>1.</font>&nbsp; Configure Bitbucket Prompt Repo</b> in Settings → Login&nbsp;&nbsp; → &nbsp;&nbsp;<b><font color='#3B82F6'>2.</font>&nbsp; Click Import Repo Config</b>&nbsp;&nbsp; → &nbsp;&nbsp;<b><font color='#3B82F6'>3.</font>&nbsp; Configure LLM and log in</b> from Settings → LLM", 600, bodyFont, fgColor))
                add(Box.createVerticalStrut(10))
                add(featureButton("Open setup settings  →") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AiTestSettingsConfigurable::class.java)
                })
            }, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(label("$setupCount / 2", commonFont.deriveFont(Font.BOLD, 18f), ThemeColors.systemAccent).apply {
                    horizontalAlignment = SwingConstants.CENTER
                    alignmentX = Component.CENTER_ALIGNMENT
                })
                add(Box.createVerticalStrut(8))
                add(JButton("Setup Guide").apply {
                    font = bodyFont
                    isFocusPainted = false
                    alignmentX = Component.CENTER_ALIGNMENT
                    addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, AiTestSettingsConfigurable::class.java) }
                })
            }, BorderLayout.EAST)
        }
        contentRoot.add(welcomePanel)
        contentRoot.add(Box.createVerticalStrut(20))
        contentRoot.add(label("What would you like to do?", sectionFont).apply { alignmentX = Component.LEFT_ALIGNMENT })
        contentRoot.add(Box.createVerticalStrut(4))
        contentRoot.add(label("Select a feature to see how it works. Drag the horizontal scroll bar to browse all eight features.", bodyFont, mutedColor).apply { alignmentX = Component.LEFT_ALIGNMENT })
        contentRoot.add(Box.createVerticalStrut(12))

        val cardsPanel = JPanel(java.awt.GridLayout(1, 0, 12, 0)).apply {
            background = pageColor
            border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
            features.forEach { add(createCard(it)) }
            preferredSize = Dimension(features.size * 218 + (features.size - 1) * 12, 176)
        }
        contentRoot.add(JBScrollPane(
            cardsPanel,
            javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(760, 210)
            maximumSize = Dimension(Int.MAX_VALUE, 210)
            border = BorderFactory.createLineBorder(borderColor)
            viewport.background = pageColor
            horizontalScrollBar.unitIncrement = 24
            horizontalScrollBar.blockIncrement = 220
            toolTipText = "Drag the horizontal scroll bar to browse all features"
        })
        contentRoot.add(Box.createVerticalStrut(20))
        contentRoot.add(detailsContainer)

        showFeature = { feature ->
            cardPanels.forEachIndexed { index, card ->
                card.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(if (features[index] == feature) accentColor else borderColor),
                    BorderFactory.createEmptyBorder(14, 14, 12, 14)
                )
            }
            val stepsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(label("How it works", commonFont.deriveFont(Font.BOLD, 15f)))
                add(Box.createVerticalStrut(8))
                feature.steps.forEachIndexed { index, step ->
                    add(html("<b><font color='#3B82F6'>${index + 1}.</font>&nbsp;&nbsp;${step.first}</b><br>&nbsp;&nbsp;&nbsp;&nbsp;${step.second}", 420, bodyFont, fgColor))
                    add(Box.createVerticalStrut(10))
                }
            }
            val factsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = surfaceColor
                    border = panelBorder()
                    add(label("You can configure", commonFont.deriveFont(Font.BOLD, 14f)))
                    add(Box.createVerticalStrut(6))
                    feature.configurable.forEach { add(label("✓  $it", smallFont, ThemeColors.categoryCodeGen)) }
                })
                add(Box.createVerticalStrut(10))
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = surfaceColor
                    border = panelBorder()
                    add(label("Best for", commonFont.deriveFont(Font.BOLD, 14f)))
                    add(Box.createVerticalStrut(6))
                    feature.bestFor.forEach { add(label("•  $it", smallFont, mutedColor)) }
                })
            }
            detailsContainer.removeAll()
            detailsContainer.add(JPanel(BorderLayout(12, 12)).apply {
                background = surfaceColor
                border = panelBorder()
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                        isOpaque = false
                        add(label("${feature.icon}  ${feature.title}", commonFont.deriveFont(Font.BOLD, 18f)))
                        add(label(feature.category, smallFont, ThemeColors.categoryTest).apply {
                            border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
                            isOpaque = true
                            background = cardColor
                        })
                    }, BorderLayout.WEST)
                    add(JButton(feature.actionLabel).apply {
                        font = bodyFont.deriveFont(Font.BOLD)
                        foreground = accentColor
                        isFocusPainted = false
                        addActionListener { feature.action() }
                    }, BorderLayout.EAST)
                    add(html(feature.intro, 560, bodyFont, mutedColor), BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
                add(JPanel(BorderLayout(20, 0)).apply {
                    isOpaque = false
                    add(stepsPanel, BorderLayout.CENTER)
                    add(factsPanel, BorderLayout.EAST)
                }, BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply {
                    background = cardColor
                    border = panelBorder()
                    add(html("<b>💡 Tips</b><br>${feature.tip}", 680, smallFont, mutedColor), BorderLayout.CENTER)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            detailsContainer.revalidate()
            detailsContainer.repaint()
        }
        showFeature(features.first())

        contentRoot.add(Box.createVerticalStrut(14))
        contentRoot.add(JPanel(BorderLayout()).apply {
            background = surfaceColor
            border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
            alignmentX = Component.LEFT_ALIGNMENT
            add(label("⚙  Setup Workflow", bodyFont, mutedColor), BorderLayout.WEST)
            add(label("${if (repoConfigured) "✓" else "○"} Repo details     2  Import config     ${if (llmConfigured) "✓" else "○"} LLM login     4  Try a feature", bodyFont, mutedColor), BorderLayout.EAST)
        })

        return JPanel(BorderLayout()).apply {
            background = bgColor
            add(JBScrollPane(contentRoot).apply {
                viewport.background = pageColor
                background = pageColor
                border = BorderFactory.createEmptyBorder()
                verticalScrollBar.unitIncrement = 16
            }, BorderLayout.CENTER)
        }
    }

    private fun isGlobalPromptName(name: String): Boolean {
        return name.startsWith("[ADA]") || name.startsWith("[repo]") || name.startsWith("global/")
    }
}
