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
        val latestResult: String
    )

    companion object {
        val TOPIC: Topic<ContextBoxListener> = Topic.create("Context Box Updates", ContextBoxListener::class.java)

        fun getInstance(project: Project): ContextBoxStateService = project.service()
    }

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var latestResult: String = "No result yet."

    fun snapshot(): Snapshot = Snapshot(latestResult)

    fun recordGeneration(className: String, targetPath: String, diff: String) {
        val now = LocalDateTime.now().format(formatter)
        latestResult = buildString {
            appendLine("Type: Generated Code")
            appendLine("Time: $now")
            appendLine("Class: $className")
            appendLine("Target: $targetPath")
            appendLine()
            appendLine("Code Diff:")
            append(diff.ifBlank { "No diff generated." })
        }.trimEnd()
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }

    fun recordBranchSummary(targetBranch: String, sourceBranch: String, summary: String) {
        val now = LocalDateTime.now().format(formatter)
        latestResult = buildString {
            appendLine("Type: Branch Analysis")
            appendLine("Time: $now")
            appendLine("Target Branch: $targetBranch")
            appendLine("Source Branch: $sourceBranch")
            appendLine()
            appendLine("Analysis:")
            append(summary.trim())
        }.trimEnd()
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }

    fun recordCodePromptResult(promptType: String, promptName: String, result: String) {
        val now = LocalDateTime.now().format(formatter)
        latestResult = buildString {
            appendLine("Type: $promptType")
            appendLine("Prompt: $promptName")
            appendLine("Time: $now")
            appendLine()
            appendLine("LLM Result:")
            append(result.trim().ifBlank { "(empty response)" })
        }.trimEnd()
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }
}

fun interface ContextBoxListener {
    fun stateUpdated(snapshot: ContextBoxStateService.Snapshot)
}
