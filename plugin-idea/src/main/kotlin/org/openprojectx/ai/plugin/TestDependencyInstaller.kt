package org.openprojectx.ai.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File

object TestDependencyInstaller {

    fun needsSetup(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        val pom = File(basePath, "pom.xml")
        if (pom.exists()) {
            val text = pom.readText(Charsets.UTF_8)
            return !text.contains("<artifactId>junit-jupiter</artifactId>") ||
                !text.contains("<artifactId>mockito-core</artifactId>") ||
                !text.contains("<artifactId>rest-assured</artifactId>")
        }

        val gradleKts = File(basePath, "build.gradle.kts")
        if (gradleKts.exists()) {
            val text = gradleKts.readText(Charsets.UTF_8)
            return !text.contains("org.junit.jupiter:junit-jupiter") ||
                !text.contains("org.mockito:mockito-core") ||
                !text.contains("io.rest-assured:rest-assured")
        }

        val gradle = File(basePath, "build.gradle")
        if (gradle.exists()) {
            val text = gradle.readText(Charsets.UTF_8)
            return !text.contains("org.junit.jupiter:junit-jupiter") ||
                !text.contains("org.mockito:mockito-core") ||
                !text.contains("io.rest-assured:rest-assured")
        }

        return false
    }

    fun installAndDownloadWithFeedback(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val basePath = project.basePath ?: error("Project base path is null")
                val result = install(basePath)
                Notifications.info(project, "Test dependencies configured", result.message)
            } catch (e: Exception) {
                Notifications.error(project, "Failed to configure test dependencies", e.message ?: e.toString())
            }
        }
    }

    private fun install(basePath: String): InstallResult {
        val pom = File(basePath, "pom.xml")
        if (pom.exists()) {
            val updated = updatePom(pom)
            val output = runCommand(basePath, listOf("./mvnw", "-q", "-DskipTests", "test-compile"))
                ?: runCommand(basePath, listOf("mvn", "-q", "-DskipTests", "test-compile"))
            return InstallResult(
                message = buildString {
                    append(if (updated) "Updated pom.xml with test dependencies. " else "pom.xml already contains required dependencies. ")
                    append(output ?: "Could not start Maven automatically; please run mvn test-compile manually.")
                }
            )
        }

        val gradleKts = File(basePath, "build.gradle.kts")
        if (gradleKts.exists()) {
            val updated = updateGradleKts(gradleKts)
            val output = runCommand(basePath, listOf("./gradlew", "testClasses"))
                ?: runCommand(basePath, listOf("gradle", "testClasses"))
            return InstallResult(
                message = buildString {
                    append(if (updated) "Updated build.gradle.kts with test dependencies. " else "build.gradle.kts already contains required dependencies. ")
                    append(output ?: "Could not start Gradle automatically; please run gradle testClasses manually.")
                }
            )
        }

        val gradle = File(basePath, "build.gradle")
        if (gradle.exists()) {
            val updated = updateGradle(gradle)
            val output = runCommand(basePath, listOf("./gradlew", "testClasses"))
                ?: runCommand(basePath, listOf("gradle", "testClasses"))
            return InstallResult(
                message = buildString {
                    append(if (updated) "Updated build.gradle with test dependencies. " else "build.gradle already contains required dependencies. ")
                    append(output ?: "Could not start Gradle automatically; please run gradle testClasses manually.")
                }
            )
        }

        return InstallResult("No supported build file found (pom.xml/build.gradle.kts/build.gradle).")
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
}
