package org.openprojectx.ai.plugin

import com.intellij.openapi.vfs.VirtualFile

object JavaHeuristics {

    private const val MAIN_JAVA_MARKER = "/src/main/java/"

    fun looksLikeJavaSource(file: VirtualFile, text: String): Boolean {
        if (!file.name.lowercase().endsWith(".java")) return false

        val hasTypeDeclaration = Regex("""(?m)^\s*(public\s+)?(class|interface|record|enum)\s+\w+""")
            .containsMatchIn(text)
        if (!hasTypeDeclaration) return false

        val hasMethodSignature = Regex("""(?m)^\s*(public|protected|private)\s+[\w<>,\[\]\s]+\s+\w+\s*\(""")
            .containsMatchIn(text)

        return hasMethodSignature
    }

    fun isTestJavaPath(file: VirtualFile): Boolean {
        val normalized = file.path.replace('\\', '/').lowercase()
        return normalized.contains("/src/test/java/")
    }

    fun deriveTestLocationForMainJava(file: VirtualFile, projectBasePath: String?): String? =
        deriveTestLocationForMainJava(file.path, projectBasePath)

    fun deriveTestLocationForMainJava(filePath: String, projectBasePath: String?): String? {
        val (modulePrefix, _) = splitAtMainJava(filePath, projectBasePath) ?: return null
        return listOfNotNull(
            modulePrefix.takeIf { it.isNotBlank() },
            "src/test/java"
        ).joinToString("/")
    }

    fun derivePackageNameForJava(file: VirtualFile, projectBasePath: String?): String? =
        derivePackageNameForJava(file.path, projectBasePath)

    fun derivePackageNameForJava(filePath: String, projectBasePath: String?): String? {
        val (_, afterMarker) = splitAtMainJava(filePath, projectBasePath) ?: return null
        val packagePath = afterMarker.substringBeforeLast('/', missingDelimiterValue = "")
        if (packagePath.isBlank()) return null
        return packagePath.replace('/', '.').trim('.').takeIf { it.isNotBlank() }
    }

    /** Reads the `package x.y.z;` declaration from generated source, or null if none is present. */
    fun extractDeclaredPackage(code: String): String? =
        code.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Splits [filePath] at the `src/main/java` source root, returning
     * `(modulePrefix, pathAfterMarker)` or null when the file is not under such a root.
     */
    private fun splitAtMainJava(filePath: String, projectBasePath: String?): Pair<String, String>? {
        val basePath = projectBasePath ?: return null
        val normalizedFilePath = filePath.replace('\\', '/')
        val normalizedBasePath = basePath.replace('\\', '/').removeSuffix("/")
        val prefix = "$normalizedBasePath/"
        if (!normalizedFilePath.startsWith(prefix)) return null

        // Prepend a leading slash so a file located directly under
        // <projectRoot>/src/main/java/... (single-module layout) matches the marker
        // exactly like a multi-module <projectRoot>/<module>/src/main/java/... file does.
        val relative = "/" + normalizedFilePath.removePrefix(prefix)
        val index = relative.indexOf(MAIN_JAVA_MARKER)
        if (index < 0) return null

        val modulePrefix = relative.substring(0, index).trim('/')
        val afterMarker = relative.substring(index + MAIN_JAVA_MARKER.length)
        return modulePrefix to afterMarker
    }
}
