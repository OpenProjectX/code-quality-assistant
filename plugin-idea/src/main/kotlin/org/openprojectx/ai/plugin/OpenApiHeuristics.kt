package org.openprojectx.ai.plugin

import com.intellij.openapi.vfs.VirtualFile

object OpenApiHeuristics {
    fun looksLikeOpenApi(file: VirtualFile, text: String): Boolean {
        val name = file.name.lowercase()
        if (!(name.endsWith(".yaml") || name.endsWith(".yml"))) return false

        val hasVersion = Regex("""(?m)^\s*(openapi|swagger)\s*:""").containsMatchIn(text)
        if (!hasVersion) return false

        val hasStructure = Regex("""(?m)^\s*(paths|components|info)\s*:""").containsMatchIn(text)
        return hasStructure
    }
}