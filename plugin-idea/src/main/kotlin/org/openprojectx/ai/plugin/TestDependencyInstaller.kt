package org.openprojectx.ai.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

object TestDependencyInstaller {

    fun needsSetup(project: Project, contextFile: VirtualFile? = null): Boolean {
        val target = resolveBuildTarget(project, contextFile) ?: return false
        val text = target.buildFile.readText(Charsets.UTF_8)
        return when (target.kind) {
            BuildKind.MAVEN -> !text.contains("<artifactId>junit-jupiter</artifactId>") ||
                !text.contains("<artifactId>mockito-core</artifactId>") ||
                !text.contains("<artifactId>rest-assured</artifactId>")
            BuildKind.GRADLE_KTS, BuildKind.GRADLE -> !text.contains("org.junit.jupiter:junit-jupiter") ||
                !text.contains("org.mockito:mockito-core") ||
                !text.contains("io.rest-assured:rest-assured")
        }
    }

    fun installAndDownloadWithFeedback(project: Project, contextFile: VirtualFile? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val target = resolveBuildTarget(project, contextFile)
                    ?: error("No supported build file found near current file or project root")
                val result = install(target, project.basePath ?: target.moduleDir.absolutePath)
                Notifications.info(project, "Test dependencies configured", result.message)
            } catch (e: Exception) {
                Notifications.error(project, "Failed to configure test dependencies", e.message ?: e.toString())
            }
        }
    }

    private fun install(target: BuildTarget, projectBasePath: String): InstallResult {
        if (target.kind == BuildKind.MAVEN) {
            val updated = updatePom(target.buildFile)
            val output = runCommand(projectBasePath, listOf("./mvnw", "-q", "-DskipTests", "test-compile"))
                ?: runCommand(projectBasePath, listOf("mvn", "-q", "-DskipTests", "test-compile"))
            return InstallResult(
                message = buildString {
                    append(if (updated) "Updated pom.xml with test dependencies. " else "pom.xml already contains required dependencies. ")
                    append(output ?: "Could not start Maven automatically; please run mvn test-compile manually.")
                }
            )
        }

        if (target.kind == BuildKind.GRADLE_KTS) {
            val updated = updateGradleKts(target.buildFile)
            val output = runCommand(projectBasePath, listOf("./gradlew", "testClasses"))
                ?: runCommand(projectBasePath, listOf("gradle", "testClasses"))
            return InstallResult(
                message = buildString {
                    append(if (updated) "Updated build.gradle.kts with test dependencies. " else "build.gradle.kts already contains required dependencies. ")
                    append(output ?: "Could not start Gradle automatically; please run gradle testClasses manually.")
                }
            )
        }

        if (target.kind == BuildKind.GRADLE) {
            val updated = updateGradle(target.buildFile)
            val output = runCommand(projectBasePath, listOf("./gradlew", "testClasses"))
                ?: runCommand(projectBasePath, listOf("gradle", "testClasses"))
            return InstallResult(
                message = buildString {
                    append(if (updated) "Updated build.gradle with test dependencies. " else "build.gradle already contains required dependencies. ")
                    append(output ?: "Could not start Gradle automatically; please run gradle testClasses manually.")
                }
            )
        }

        return InstallResult("No supported build file found.")
    }

    private fun updateGradleKts(file: File): Boolean {
        val text = file.readText(Charsets.UTF_8)
        if (text.contains("org.junit.jupiter:junit-jupiter")) return false

        val snippet = """

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("io.rest-assured:rest-assured:5.4.0")
}

tasks.test {
    useJUnitPlatform()
}
""".trimEnd()
        file.writeText(text.trimEnd() + "\n" + snippet + "\n", Charsets.UTF_8)
        return true
    }

    private fun updateGradle(file: File): Boolean {
        val text = file.readText(Charsets.UTF_8)
        if (text.contains("org.junit.jupiter:junit-jupiter")) return false

        val snippet = """

dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testImplementation 'org.mockito:mockito-core:5.11.0'
    testImplementation 'io.rest-assured:rest-assured:5.4.0'
}

test {
    useJUnitPlatform()
}
""".trimEnd()
        file.writeText(text.trimEnd() + "\n" + snippet + "\n", Charsets.UTF_8)
        return true
    }

    private fun updatePom(file: File): Boolean {
        val text = file.readText(Charsets.UTF_8)
        if (text.contains("<artifactId>junit-jupiter</artifactId>")) return false

        val deps = """
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.11.0</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.rest-assured</groupId>
            <artifactId>rest-assured</artifactId>
            <version>5.4.0</version>
            <scope>test</scope>
        </dependency>
        """.trimIndent()

        val updated = when {
            text.contains("</dependencies>") -> text.replace("</dependencies>", "$deps\n    </dependencies>")
            text.contains("</project>") -> text.replace("</project>", "  <dependencies>\n$deps\n  </dependencies>\n</project>")
            else -> text + "\n<dependencies>\n$deps\n</dependencies>\n"
        }
        file.writeText(updated, Charsets.UTF_8)
        return true
    }

    private fun runCommand(basePath: String, cmd: List<String>): String? {
        return try {
            val process = ProcessBuilder(cmd)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            if (code == 0) "Dependency download completed (${cmd.joinToString(" ")})." else {
                "Command failed (${cmd.joinToString(" ")}): ${output.take(300)}"
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class InstallResult(
        val message: String
    )

    private enum class BuildKind {
        MAVEN,
        GRADLE_KTS,
        GRADLE
    }

    private data class BuildTarget(
        val moduleDir: File,
        val buildFile: File,
        val kind: BuildKind
    )

    private fun resolveBuildTarget(project: Project, contextFile: VirtualFile?): BuildTarget? {
        val projectBasePath = project.basePath ?: return null
        val projectBaseDir = File(projectBasePath)

        if (contextFile != null) {
            var dir: File? = File(contextFile.path).parentFile
            while (dir != null && dir.path.startsWith(projectBaseDir.path)) {
                locateBuildFile(dir)?.let { return it }
                dir = dir.parentFile
            }
        }

        return locateBuildFile(projectBaseDir)
    }

    private fun locateBuildFile(dir: File): BuildTarget? {
        val pom = File(dir, "pom.xml")
        if (pom.exists()) return BuildTarget(dir, pom, BuildKind.MAVEN)

        val gradleKts = File(dir, "build.gradle.kts")
        if (gradleKts.exists()) return BuildTarget(dir, gradleKts, BuildKind.GRADLE_KTS)

        val gradle = File(dir, "build.gradle")
        if (gradle.exists()) return BuildTarget(dir, gradle, BuildKind.GRADLE)

        return null
    }
}
