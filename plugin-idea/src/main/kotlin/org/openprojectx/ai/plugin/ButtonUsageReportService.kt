package org.openprojectx.ai.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Service(Service.Level.PROJECT)
class ButtonUsageReportService(private val project: Project) {
    private val counts: MutableMap<String, Int> = linkedMapOf()
    private val promptFeatureCounts: MutableMap<String, Int> = linkedMapOf()
    private val promptUsageCounts: MutableMap<String, Int> = linkedMapOf()

    companion object {
        fun getInstance(project: Project): ButtonUsageReportService = project.service()
    }

    @Synchronized
    fun record(buttonKey: String) {
        val current = counts[buttonKey] ?: 0
        counts[buttonKey] = current + 1
        save()
    }

    @Synchronized
    fun recordPromptUsage(featureKey: String, promptName: String) {
        val normalizedFeature = featureKey.trim().ifBlank { "unknown" }
        val normalizedPrompt = promptName.trim().ifBlank { "default" }
        val featureCurrent = promptFeatureCounts[normalizedFeature] ?: 0
        promptFeatureCounts[normalizedFeature] = featureCurrent + 1

        val key = "$normalizedFeature::$normalizedPrompt"
        val current = promptUsageCounts[key] ?: 0
        promptUsageCounts[key] = current + 1
        save()
    }

    @Synchronized
    private fun save() {
        val root = linkedMapOf<String, Any>(
            "project" to (project.name.ifBlank { "unknown" }),
            "updatedAt" to Instant.now().toString(),
            "buttonUsageCounts" to counts,
            "promptFeatureUsageCounts" to promptFeatureCounts,
            "promptUsageCounts" to promptUsageCounts
        )
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            isPrettyFlow = true
        }
        runCatching {
            val text = Yaml(options).dump(root)
            Files.writeString(reportPath(), text)
        }
    }

    private fun reportPath(): Path {
        val base = project.basePath?.let { Path.of(it) } ?: Path.of(".")
        return base.resolve(".ai-test-button-usage-report.yaml")
    }

    init {
        runCatching {
            val path = reportPath()
            if (!Files.exists(path)) return@runCatching
            val root = Yaml().load<Any?>(Files.readString(path)) as? Map<*, *> ?: return@runCatching
            val usage = root["buttonUsageCounts"] as? Map<*, *> ?: return@runCatching
            usage.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString()?.toIntOrNull() ?: 0
                if (key.isNotBlank()) counts[key] = value
            }

            val promptFeatureUsage = root["promptFeatureUsageCounts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            promptFeatureUsage.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString()?.toIntOrNull() ?: 0
                if (key.isNotBlank()) promptFeatureCounts[key] = value
            }

            val promptUsage = root["promptUsageCounts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            promptUsage.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString()?.toIntOrNull() ?: 0
                if (key.isNotBlank()) promptUsageCounts[key] = value
            }
        }
    }
}
