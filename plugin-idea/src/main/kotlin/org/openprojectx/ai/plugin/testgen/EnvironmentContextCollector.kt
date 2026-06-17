package org.openprojectx.ai.plugin.testgen

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.LanguageLevelModuleExtension
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

/** Toolchain facts about the module under test that the LLM cannot infer from the production source alone. */
data class EnvironmentInfo(
    val languageLevel: String?,
    val buildTool: String?,
    val testLibraries: List<String>,
    val keyVersions: List<String>,
    val lombokPresent: Boolean
)

/**
 * Detects the test toolchain of the module a source file belongs to (available test libraries,
 * Java language level, behavior-defining library versions) and renders it as a prompt block, so the
 * generated tests only use libraries that exist and reason correctly about version-sensitive behavior.
 */
object EnvironmentContextCollector {

    fun collect(project: Project, sourceFile: VirtualFile): String {
        return ReadAction.compute<String, RuntimeException> {
            val module = ModuleUtilCore.findModuleForFile(sourceFile, project) ?: return@compute ""
            val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true)
            val facade = JavaPsiFacade.getInstance(project)
            fun has(fqn: String): Boolean = facade.findClass(fqn, scope) != null

            val testLibraries = buildList {
                when {
                    has("org.junit.jupiter.api.Test") -> add("JUnit 5")
                    has("org.junit.Test") -> add("JUnit 4")
                }
                if (has("org.assertj.core.api.Assertions")) add("AssertJ")
                if (has("org.mockito.Mockito")) {
                    add(if (has("org.mockito.junit.jupiter.MockitoExtension")) "Mockito (+ mockito-junit-jupiter)" else "Mockito")
                }
                if (has("org.springframework.boot.test.context.SpringBootTest")) add("Spring Boot Test")
                if (has("org.hamcrest.MatcherAssert")) add("Hamcrest")
            }

            val lombokPresent = has("lombok.Builder") || has("lombok.Data")

            // Versions only for libraries whose behavior is version-sensitive enough to matter.
            val versionTargets = linkedMapOf(
                "lombok" to "Lombok",
                "spring-boot" to "Spring Boot",
                "junit-jupiter-api" to "JUnit Jupiter",
                "assertj-core" to "AssertJ",
                "mockito-core" to "Mockito"
            )
            val foundVersions = linkedMapOf<String, String>()
            OrderEnumerator.orderEntries(module).librariesOnly().forEachLibrary { lib ->
                val name = lib.name
                if (name != null) {
                    for ((artifact, label) in versionTargets) {
                        if (!foundVersions.containsKey(label)) {
                            parseLibraryVersion(name, artifact)?.let { foundVersions[label] = "$label $it" }
                        }
                    }
                }
                true
            }

            val languageLevel = runCatching {
                val level = ModuleRootManager.getInstance(module)
                    .getModuleExtension(LanguageLevelModuleExtension::class.java)
                    ?.languageLevel
                    ?: LanguageLevelProjectExtension.getInstance(project).languageLevel
                level.toJavaVersion().feature.toString()
            }.getOrNull()

            formatEnvironmentBlock(
                EnvironmentInfo(
                    languageLevel = languageLevel,
                    buildTool = detectBuildTool(project),
                    testLibraries = testLibraries,
                    keyVersions = foundVersions.values.toList(),
                    lombokPresent = lombokPresent
                )
            )
        }
    }

    /**
     * Fully-qualified marker classes whose presence means the module has at least one usable test
     * library. Superset of the libraries listed in the prompt block — it also includes REST Assured,
     * which matters for the OpenAPI→REST Assured generation path but is not a Java-unit-test assertion lib.
     */
    private val TEST_LIBRARY_MARKERS = listOf(
        "org.junit.jupiter.api.Test",                           // JUnit 5
        "org.junit.Test",                                       // JUnit 4
        "org.assertj.core.api.Assertions",                      // AssertJ
        "org.mockito.Mockito",                                  // Mockito
        "org.springframework.boot.test.context.SpringBootTest", // Spring Boot Test
        "org.hamcrest.MatcherAssert",                           // Hamcrest
        "io.restassured.RestAssured"                            // REST Assured
    )

    /** Pure decision: true when NONE of the recognized test-library markers are present. */
    fun noTestLibrariesPresent(presentClassNames: Collection<String>): Boolean =
        TEST_LIBRARY_MARKERS.none { it in presentClassNames }

    /**
     * True when the module owning [sourceFile] has no recognized test library on its classpath.
     * Returns false when the module can't be resolved, so an undetermined environment never raises a
     * false warning. Read-only and side-effect free; does not affect the prompt sent to the LLM.
     */
    fun hasNoTestLibrariesOnClasspath(project: Project, sourceFile: VirtualFile): Boolean {
        return ReadAction.compute<Boolean, RuntimeException> {
            val module = ModuleUtilCore.findModuleForFile(sourceFile, project) ?: return@compute false
            val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true)
            val facade = JavaPsiFacade.getInstance(project)
            val present = TEST_LIBRARY_MARKERS.filter { facade.findClass(it, scope) != null }
            noTestLibrariesPresent(present)
        }
    }

    /** Extracts the version from an IntelliJ library name like `Maven: org.projectlombok:lombok:1.18.30`. */
    fun parseLibraryVersion(libraryName: String, artifactId: String): String? {
        val coordinate = libraryName.substringAfter(": ", libraryName).trim()
        val parts = coordinate.split(':')
        if (parts.size < 3 || parts[1] != artifactId) return null
        return parts[2].takeIf { it.isNotBlank() }
    }

    /** Renders [info] as a markdown prompt block, or "" when nothing was detected. */
    fun formatEnvironmentBlock(info: EnvironmentInfo): String {
        val lines = mutableListOf<String>()
        info.languageLevel?.let { lines += "- Java language level: $it" }
        info.buildTool?.let { lines += "- Build tool: $it" }
        if (info.testLibraries.isNotEmpty()) {
            lines += "- Test libraries available: ${info.testLibraries.joinToString(", ")}"
        }
        if (info.keyVersions.isNotEmpty()) {
            lines += "- Key versions: ${info.keyVersions.joinToString(", ")}"
        }
        if (info.lombokPresent) {
            lines += "- Notes: Lombok present — @Builder.Default field initialization differs by creation path " +
                "(builder vs constructor) and by Lombok version; prefer empty/default or robust assertions over " +
                "guessing null. Do not import test libraries not listed above."
        }
        if (lines.isEmpty()) return ""
        return (listOf("## Test Environment (use ONLY what is listed here)") + lines).joinToString("\n")
    }

    private fun detectBuildTool(project: Project): String? {
        val root = project.guessProjectDir() ?: return null
        return when {
            root.findChild("pom.xml") != null -> "Maven"
            root.findChild("build.gradle") != null || root.findChild("build.gradle.kts") != null -> "Gradle"
            else -> null
        }
    }
}
