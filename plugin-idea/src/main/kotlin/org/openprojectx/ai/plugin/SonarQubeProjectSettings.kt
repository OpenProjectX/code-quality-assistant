package org.openprojectx.ai.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.Yaml
import java.io.File

@Service(Service.Level.PROJECT)
class SonarQubeProjectSettings(private val project: Project) {

    companion object {
        fun getInstance(project: Project): SonarQubeProjectSettings =
            project.getService(SonarQubeProjectSettings::class.java)

        private fun projectDirName(project: Project): String {
            val basePath = project.basePath ?: project.name
            return basePath.replace(Regex("""[\\/:]"""), "-")
        }
    }

    private val configDir: File
        get() = File(LlmSettingsLoader.skillsDir().parentFile, "projects/${projectDirName(project)}")
            .also { it.mkdirs() }

    private val configFile: File
        get() = File(configDir, "sonar-project.yaml")

    private val yaml = Yaml()

    var projectKey: String
        get() = getValue("projectKey")
        set(value) = setValue("projectKey", value.trim())

    var serverUrl: String
        get() = getValue("serverUrl")
        set(value) = setValue("serverUrl", value.trim())

    fun resolveConfig(): SonarQubeConfig {
        val global = runCatching { LlmSettingsLoader.loadSonarQubeConfig(project) }
            .getOrDefault(SonarQubeConfig())
        val local = readMap() ?: emptyMap()
        return SonarQubeConfig(
            serverUrl = getValue(local, "serverUrl").ifBlank { global.serverUrl },
            projectKey = getValue(local, "projectKey").ifBlank { global.projectKey },
            token = global.token,
            tokenEnv = global.tokenEnv,
            username = global.username,
            password = global.password,
            passwordEnv = global.passwordEnv,
            targetCoverage = global.targetCoverage,
            maxFiles = global.maxFiles,
            mockEnabled = global.mockEnabled,
            localScanEnabled = global.localScanEnabled
        )
    }

    private fun getValue(key: String): String {
        return readMap().let { map -> getValue(map ?: emptyMap(), key) }
    }

    private fun setValue(key: String, value: String) {
        val map = LinkedHashMap(readMap() ?: emptyMap())
        map[key] = value
        writeMap(map)
    }

    private fun getValue(map: Map<String, Any>, key: String): String =
        (map[key] as? String)?.trim().orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun readMap(): Map<String, Any>? {
        if (!configFile.exists()) return null
        return runCatching {
            yaml.load<Any?>(configFile.readText(Charsets.UTF_8)) as? Map<String, Any>
        }.getOrNull()
    }

    private fun writeMap(map: LinkedHashMap<String, Any>) {
        configDir.mkdirs()
        configFile.writeText(yaml.dump(map), Charsets.UTF_8)
    }
}
