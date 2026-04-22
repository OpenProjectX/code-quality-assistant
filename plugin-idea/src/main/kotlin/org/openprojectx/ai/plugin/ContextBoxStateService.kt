package org.openprojectx.ai.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class ContextBoxStateService(private val project: Project) {

    data class Snapshot(
        val latestResult: String,
        val history: List<String>
    )

    companion object {
        val TOPIC: Topic<ContextBoxListener> = Topic.create("Context Box Updates", ContextBoxListener::class.java)

        fun getInstance(project: Project): ContextBoxStateService = project.service()
    }

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val history = mutableListOf<String>()

    fun snapshot(): Snapshot {
        val latest = history.lastOrNull() ?: "No result yet."
        return Snapshot(latestResult = latest, history = history.toList())
    }

    fun recordGeneration(className: String, targetPath: String, diff: String) {
        val now = LocalDateTime.now().format(formatter)
        appendResult(buildString {
            appendLine("Type: Generated Code")
            appendLine("Time: $now")
            appendLine("Class: $className")
            appendLine("Target: $targetPath")
            appendLine()
            appendLine("Code Diff:")
            append(diff.ifBlank { "No diff generated." })
        }.trimEnd())
    }

    fun recordBranchSummary(targetBranch: String, sourceBranch: String, summary: String) {
        val now = LocalDateTime.now().format(formatter)
        appendResult(buildString {
            appendLine("Type: Branch Analysis")
            appendLine("Time: $now")
            appendLine("Target Branch: $targetBranch")
            appendLine("Source Branch: $sourceBranch")
            appendLine()
            appendLine("Analysis:")
            append(summary.trim())
        }.trimEnd())
    }

    fun recordCodePromptResult(promptType: String, promptName: String, result: String) {
        val now = LocalDateTime.now().format(formatter)
        appendResult(buildString {
            appendLine("Type: $promptType")
            appendLine("Prompt: $promptName")
            appendLine("Time: $now")
            appendLine()
            appendLine("LLM Result:")
            append(result.trim().ifBlank { "(empty response)" })
        }.trimEnd())
    }

    fun recordFollowUp(extraRequirement: String, result: String) {
        val now = LocalDateTime.now().format(formatter)
        appendResult(buildString {
            appendLine("Type: Follow-up")
            appendLine("Time: $now")
            appendLine("Extra Requirement: ${extraRequirement.trim().ifBlank { "(none)" }}")
            appendLine()
            appendLine("LLM Result:")
            append(result.trim().ifBlank { "(empty response)" })
        }.trimEnd())
    }

    fun clearHistory() {
        history.clear()
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }

    private fun appendResult(result: String) {
        history += result
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }
}

fun interface ContextBoxListener {
    fun stateUpdated(snapshot: ContextBoxStateService.Snapshot)
}
