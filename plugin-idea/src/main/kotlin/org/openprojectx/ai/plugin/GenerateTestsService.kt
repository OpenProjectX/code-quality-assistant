package org.openprojectx.ai.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import org.openprojectx.ai.plugin.core.ContractType
import org.openprojectx.ai.plugin.core.Framework
import org.openprojectx.ai.plugin.core.GenerationRequest
import org.openprojectx.ai.plugin.core.PromptBuilder
import org.openprojectx.ai.plugin.testgen.DependentMethodCollector
import org.openprojectx.ai.plugin.testgen.EnvironmentContextCollector
import org.slf4j.LoggerFactory


class GenerateTestsService(private val project: Project) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val notificationState = OpenApiNotificationStateService.getInstance(project)
    private val usage = ButtonUsageReportService.getInstance(project)

    fun generate(ui: GenerateTestsDialog.UiResult, file: VirtualFile, contractText: String) {
        notificationState.setState(file.path, GenerationUiState.Generating)
        EditorNotifications.getInstance(project).updateNotifications(file)

        val authSession = LlmAuthSessionService.getInstance(project)
        val config = LlmSettingsLoader.loadConfig(project)

        val contractType = when {
            OpenApiHeuristics.looksLikeOpenApi(file, contractText) -> ContractType.OPENAPI
            JavaHeuristics.looksLikeJavaSource(file, contractText) -> ContractType.JAVA
            else -> ContractType.OPENAPI
        }

        val effectiveFramework = if (contractType == ContractType.JAVA) {
            Framework.REST_ASSURED
        } else {
            ui.framework
        }

        val effectiveLocation = if (contractType == ContractType.JAVA) {
            JavaHeuristics.deriveTestLocationForMainJava(file, project.basePath)
                ?.takeIf { it.isNotBlank() }
                ?: ui.location
        } else {
            ui.location
        }

        val packageName = when (val frameworkConfig = ui.frameworkConfig) {
            is FrameworkUiConfig.RestAssured -> frameworkConfig.packageName
            FrameworkUiConfig.None -> null
        }?.takeIf { it.isNotBlank() } ?: if (contractType == ContractType.JAVA) {
            JavaHeuristics.derivePackageNameForJava(file, project.basePath)
        } else null

        val dependentMethodSignatures = if (contractType == ContractType.JAVA) {
            DependentMethodCollector.collect(project, file)
        } else ""

        val environmentContext = if (contractType == ContractType.JAVA) {
            EnvironmentContextCollector.collect(project, file)
        } else ""

        val req = GenerationRequest(
            contractText = contractText,
            framework = effectiveFramework,
            contractType = contractType,
            baseUrl = ui.baseUrl,
            location = effectiveLocation,
            packageName = packageName,
            className = ui.className,
            outputNotes = ui.notes,
            dependentMethodSignatures = dependentMethodSignatures,
            environmentContext = environmentContext
        )

        val generationTemplate = config.prompts.generation.copy(
            wrapper = ui.generationPromptWrapperOverride
                ?: PromptProfileResolver.resolve(
                    config.prompts.profiles.generation,
                    config.prompts.generation.wrapper
                )
        )
        val prompt = PromptBuilder.build(req, generationTemplate)
        usage.recordPromptUsage("test.generate", ui.generationPromptProfileName.ifBlank { "default" })

        // Show user request in Context Box
        val userMessage = buildString {
            appendLine("Generate tests for ${ui.className}")
            appendLine("Framework: ${effectiveFramework.name}")
            if (!ui.notes.isNullOrBlank()) appendLine("Notes: ${ui.notes}")
        }
        ContextBoxStateService.getInstance(project).addUserMessage(userMessage)

        val contextBox = ContextBoxStateService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating tests for ${ui.className}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Preparing request for ${ui.className}..."
                    indicator.fraction = 0.1
                    contextBox.addAssistantMessage("[1/4] Preparing request for ${ui.className}...", "Progress")

                    if (indicator.isCanceled) return

                    indicator.text = "Calling LLM..."
                    indicator.isIndeterminate = true
                    contextBox.addAssistantMessage("[2/4] Calling LLM — this may take a moment...", "Progress")

                    val code = authSession.withReloginOnUnauthorized { settings ->
                        val provider = LlmProviderFactory.create(settings)
                        kotlinx.coroutines.runBlocking { provider.generateCode(prompt) }
                    }
                    indicator.isIndeterminate = false

                    if (indicator.isCanceled) return

                    indicator.text = "Processing result..."
                    indicator.fraction = 0.8
                    contextBox.addAssistantMessage("[3/4] Processing and sanitizing generated code...", "Progress")
                    val sanitizedCode = sanitizeGeneratedCode(code)

                    indicator.text = "Writing ${ui.className}.java..."
                    indicator.fraction = 0.95
                    contextBox.addAssistantMessage("[4/4] Writing ${ui.className} to disk...", "Progress")
                    writeGenerated(
                        project = project,
                        framework = effectiveFramework,
                        location = effectiveLocation,
                        packageName = packageName,
                        cls = ui.className,
                        code = sanitizedCode
                    )

                    notificationState.setState(file.path, GenerationUiState.Done)
                    EditorNotifications.getInstance(project).updateNotifications(file)

                    ApplicationManager.getApplication().invokeLater {
                        Thread {
                            Thread.sleep(3000)
                            notificationState.clearState(file.path)
                            ApplicationManager.getApplication().invokeLater {
                                EditorNotifications.getInstance(project).updateNotifications(file)
                            }
                        }.start()
                    }
                } catch (e: Exception) {
                    log.error("Test generation failed:", e)
                    notificationState.clearState(file.path)
                    EditorNotifications.getInstance(project).updateNotifications(file)

                    val userMessage = buildActionableErrorMessage(e)
                    contextBox.addAssistantMessage("Test generation failed: $userMessage", "Error")
                    Notifications.errorWithLogs(project, "Test generation failed", userMessage)
                }
            }
        })
    }

    private fun writeGenerated(
        project: Project,
        framework: Framework,
        location: String,
        packageName: String?,
        cls: String,
        code: String
    ) {
        val projectRoot = project.guessProjectDir()
            ?: throw IllegalStateException("Cannot resolve project root")

        // Drive the output directory from the package the LLM actually declared in the file,
        // not the package we asked for. A custom prompt (e.g. "match the source file's package")
        // can make the model deviate from the requested package; without this, the file would be
        // written under the requested package path while declaring a different one, which IntelliJ
        // flags as "Package name does not correspond to the file path".
        val effectivePackage = JavaHeuristics.extractDeclaredPackage(code) ?: packageName
        val normalizedLocation = resolveOutputLocation(
            framework = framework,
            location = location,
            packageName = effectivePackage
        )

        val fileName = when (framework) {
            Framework.REST_ASSURED -> "$cls.java"
            Framework.KARATE -> "$cls.feature"
        }

        var targetFile: VirtualFile? = null
        var previousContent: String? = null

        WriteCommandAction.runWriteCommandAction(project) {
            val targetDir = if (normalizedLocation.isBlank()) {
                projectRoot
            } else {
                VfsUtil.createDirectoryIfMissing(projectRoot, normalizedLocation)
                    ?: throw IllegalStateException("Cannot create target directory: $normalizedLocation")
            }

            val existing = targetDir.findChild(fileName)
            previousContent = existing?.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            val target = existing ?: targetDir.createChildData(this, fileName)
            target.setBinaryContent(code.toByteArray(Charsets.UTF_8))
            targetFile = target
        }

        val createdFile = targetFile ?: return
        val relativePath = createdFile.path.removePrefix(projectRoot.path).removePrefix("/")

        Notifications.notifyFileGenerated(
            project = project,
            title = "Test file generated",
            message = relativePath,
            file = createdFile
        )

        ContextBoxStateService.getInstance(project).recordGeneration(
            className = cls,
            targetPath = relativePath,
            diff = buildCodeDiff(relativePath, previousContent, code)
        )
    }


    private fun buildCodeDiff(path: String, before: String?, after: String): String {
        val beforeText = before ?: ""
        if (beforeText == after) {
            return "No content change for $path"
        }

        val beforeLines = beforeText.lines()
        val afterLines = after.lines()

        val builder = StringBuilder()
        builder.append("--- a/").append(path).append("\n")
        builder.append("+++ b/").append(path).append("\n")
        builder.append("@@ -1,").append(beforeLines.size).append(" +1,").append(afterLines.size).append(" @@\n")

        if (beforeLines.isNotEmpty()) {
            beforeLines.forEach { builder.append('-').append(it).append("\n") }
        }
        if (afterLines.isNotEmpty()) {
            afterLines.forEach { builder.append('+').append(it).append("\n") }
        }

        return builder.toString().trimEnd()
    }

    private fun resolveOutputLocation(
        framework: Framework,
        location: String,
        packageName: String?
    ): String {
        val normalizedLocation = normalizePath(location)

        return when (framework) {
            Framework.REST_ASSURED -> {
                val packagePath = packageName
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.replace('.', '/')
                    ?.replace('\\', '/')
                    ?.trim('/')

                listOfNotNull(
                    normalizedLocation.takeIf { it.isNotBlank() },
                    packagePath?.takeIf { it.isNotBlank() }
                ).joinToString("/")
            }

            Framework.KARATE -> normalizedLocation
        }
    }

    private fun normalizePath(path: String): String =
        path.trim()
            .replace('\\', '/')
            .removePrefix("/")
            .removeSuffix("/")

    private fun buildActionableErrorMessage(e: Exception): String {
        val raw = e.message ?: e.javaClass.simpleName
        return when {
            raw.contains("401") || raw.contains("unauthorized", ignoreCase = true) ->
                "Authentication failed. Check your LLM provider credentials in Settings > AI Test Assistant."
            raw.contains("timeout", ignoreCase = true) || raw.contains("SocketTimeout") ->
                "Request timed out. The LLM endpoint may be slow or unreachable. Check network/settings."
            raw.contains("packageName is required", ignoreCase = true) ->
                "Package name is missing. Fill in the Package Name field in the dialog, or verify the source file is under src/main/java."
            raw.contains("Cannot resolve project root") ->
                "Could not determine project root. Ensure the project is properly opened in IntelliJ."
            raw.contains("Cannot create target directory") ->
                "Could not create the output directory. Check that the Location path is valid and writable."
            raw.contains("403") || raw.contains("forbidden", ignoreCase = true) ->
                "Access denied by the LLM provider. Verify your API key/token in Settings > AI Test Assistant."
            raw.contains("Connection refused") || raw.contains("UnknownHost") ->
                "Cannot reach the LLM endpoint. Check the provider URL and your network connection."
            else -> raw
        }
    }

    private fun sanitizeGeneratedCode(raw: String): String {
        val trimmed = raw.trim()
        val withoutStartFence = trimmed.replaceFirst(Regex("^```(?:\\w+)?\\s*\\n?"), "")
        val withoutEndFence = withoutStartFence.replaceFirst(Regex("\\n?```\\s*$"), "")
        return withoutEndFence
            .replace(Regex("\\.{3,}\\s*$"), "")
            .lines().dropLastWhile { it.isBlank() }.joinToString("\n")
            .trim()
    }
}
