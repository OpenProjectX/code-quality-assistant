package org.openprojectx.ai.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class ContextBoxStateService(private val project: Project) {

    data class ChatMessage(
        val role: Role,
        val content: String,
        val typeLabel: String = "",
        val timestamp: Instant = Instant.now(),
        val sourceBranch: String? = null,
        val targetBranch: String? = null,
        val testTargetPath: String? = null,
        val testClassName: String? = null
    ) {
        enum class Role { USER, ASSISTANT, SYSTEM }

        val formattedTime: String
            get() = LocalDateTime.ofInstant(timestamp, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    data class Snapshot(
        val latestResult: String,
        val history: List<ChatMessage>
    )

    companion object {
        val TOPIC: Topic<ContextBoxListener> = Topic.create("Context Box Updates", ContextBoxListener::class.java)

        fun getInstance(project: Project): ContextBoxStateService = project.service()
    }

    private val history = mutableListOf<ChatMessage>()
    private val branchDiffInProgress = AtomicBoolean(false)

    fun tryStartBranchDiff(): Boolean = branchDiffInProgress.compareAndSet(false, true)
    fun finishBranchDiff() { branchDiffInProgress.set(false) }

    fun snapshot(): Snapshot {
        val latest = history.lastOrNull()
        val preview = latest?.content?.lines()?.firstOrNull()?.take(80) ?: "No result yet."
        return Snapshot(latestResult = preview, history = history.toList())
    }

    fun recordGeneration(className: String, targetPath: String, diff: String) {
        append(ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            typeLabel = "Generated Code",
            content = diff,
            testTargetPath = targetPath,
            testClassName = className
        ))
    }

    fun recordBranchSummary(targetBranch: String, sourceBranch: String, summary: String) {
        append(ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            typeLabel = "Branch Analysis",
            content = "$sourceBranch → $targetBranch\n\n$summary",
            sourceBranch = sourceBranch,
            targetBranch = targetBranch
        ))
    }

    fun recordCodePromptResult(promptType: String, promptName: String, result: String) {
        append(ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            typeLabel = promptType,
            content = "Prompt: $promptName\n\n${result.ifBlank { "(empty response)" }}"
        ))
    }

    fun recordSonarQubeCoverage(projectKey: String, coverageSummary: String, generation: String) {
        val content = buildString {
            appendLine("Project: $projectKey")
            appendLine()
            appendLine(coverageSummary.ifBlank { "(empty coverage summary)" })
            if (generation.isNotBlank()) {
                appendLine()
                appendLine("--- Generated Missing Tests ---")
                append(generation)
            }
        }.trimEnd()
        append(ChatMessage(
            role = ChatMessage.Role.SYSTEM,
            typeLabel = "SonarQube Coverage",
            content = content
        ))
    }

    fun recordFollowUp(extraRequirement: String, result: String) {
        val userText = extraRequirement.trim().ifBlank { "(none)" }
        val aiText = result.trim().ifBlank { "(empty response)" }
        append(ChatMessage(role = ChatMessage.Role.USER, content = userText))
        append(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = aiText))
    }

    fun recordChat(userMessage: String, aiResponse: String) {
        append(ChatMessage(role = ChatMessage.Role.USER, content = userMessage.trim()))
        append(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = aiResponse.trim()))
    }

    fun addUserMessage(content: String) {
        append(ChatMessage(role = ChatMessage.Role.USER, content = content))
    }

    fun clearHistory() {
        history.clear()
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }

    private fun append(message: ChatMessage) {
        history += message
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }
}

fun interface ContextBoxListener {
    fun stateUpdated(snapshot: ContextBoxStateService.Snapshot)
}
