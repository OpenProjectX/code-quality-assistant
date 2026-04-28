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
        val root = readRootMap()
        val projects = linkedMapOf<String, Any>()
        (root["projects"] as? Map<*, *>)
            ?.forEach { (key, value) ->
                val name = key?.toString()?.trim().orEmpty()
                if (name.isNotBlank() && value != null) {
                    projects[name] = value
                }
            }

        projects[projectKey()] = linkedMapOf<String, Any>(
            "project" to (project.name.ifBlank { "unknown" }),
            "updatedAt" to Instant.now().toString(),
            "buttonUsageCounts" to counts,
            "promptFeatureUsageCounts" to promptFeatureCounts,
            "promptUsageCounts" to promptUsageCounts
        )

        root["updatedAt"] = Instant.now().toString()
        root["projects"] = projects

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
        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }
            ?: "."
        return Path.of(userHome).resolve(".ai-test-button-usage-report.yaml")
    }

    private fun projectKey(): String {
        return project.basePath?.ifBlank { project.name } ?: project.name
    }

    private fun readRootMap(): MutableMap<String, Any> {
        val path = reportPath()
        if (!Files.exists(path)) return linkedMapOf()
        val loaded = Yaml().load<Any?>(Files.readString(path)) as? Map<*, *> ?: return linkedMapOf()
        val result = linkedMapOf<String, Any>()
        loaded.forEach { (k, v) ->
            val key = k?.toString()?.trim().orEmpty()
            if (key.isNotBlank() && v != null) {
                result[key] = v
            }
        }
        return result
    }

    init {
        runCatching {
            val root = readRootMap()
            val projects = root["projects"] as? Map<*, *> ?: return@runCatching
            val current = projects[projectKey()] as? Map<*, *> ?: return@runCatching
            val usage = current["buttonUsageCounts"] as? Map<*, *> ?: return@runCatching
            usage.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString()?.toIntOrNull() ?: 0
                if (key.isNotBlank()) counts[key] = value
            }

            val promptFeatureUsage = current["promptFeatureUsageCounts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            promptFeatureUsage.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString()?.toIntOrNull() ?: 0
                if (key.isNotBlank()) promptFeatureCounts[key] = value
            }

            val promptUsage = current["promptUsageCounts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            promptUsage.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString()?.toIntOrNull() ?: 0
                if (key.isNotBlank()) promptUsageCounts[key] = value
            }
        }
    }
}
