package org.openprojectx.ai.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class ContextBoxStateService(private val project: Project) {

    data class Entry(
        val className: String,
        val targetPath: String,
        val timestamp: String
    )

    data class Snapshot(
        val entries: List<Entry>,
        val latestDiff: String,
        val latestBranchSummary: String
    )

    companion object {
        val TOPIC: Topic<ContextBoxListener> = Topic.create("Context Box Updates", ContextBoxListener::class.java)

        fun getInstance(project: Project): ContextBoxStateService = project.service()
    }

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val entries = mutableListOf<Entry>()
    private var latestDiff: String = "No generated diff yet."
    private var latestBranchSummary: String = "No branch diff summary yet."

    fun snapshot(): Snapshot = Snapshot(entries.toList(), latestDiff, latestBranchSummary)

    fun recordGeneration(className: String, targetPath: String, diff: String) {
        entries.add(
            0,
            Entry(
                className = className,
                targetPath = targetPath,
                timestamp = LocalDateTime.now().format(formatter)
            )
        )
        latestDiff = diff.ifBlank { "No diff generated." }
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }

    fun recordBranchSummary(targetBranch: String, sourceBranch: String, summary: String) {
        latestBranchSummary = buildString {
            appendLine("Target Branch: $targetBranch")
            appendLine("Source Branch: $sourceBranch")
            appendLine()
            appendLine("Analysis:")
            append(summary.trim())
        }.trimEnd()
        project.messageBus.syncPublisher(TOPIC).stateUpdated(snapshot())
    }
}

fun interface ContextBoxListener {
    fun stateUpdated(snapshot: ContextBoxStateService.Snapshot)
}
