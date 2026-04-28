package org.openprojectx.ai.plugin

object GitCredentialHelper {

    data class Credential(
        val username: String,
        val password: String
    )

    fun resolve(url: String): Credential? {
        return runCatching {
            val process = ProcessBuilder("git", "credential", "fill")
                .redirectErrorStream(true)
                .start()

            process.outputStream.bufferedWriter(Charsets.UTF_8).use { out ->
                out.write("url=$url\n\n")
                out.flush()
            }

            val lines = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
            process.waitFor()
            val values = lines.mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()

            val user = values["username"].orEmpty().trim()
            val pass = values["password"].orEmpty().trim()
            if (user.isBlank() || pass.isBlank()) null else Credential(user, pass)
        }.getOrNull()
    }
}
