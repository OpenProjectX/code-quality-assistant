package org.openprojectx.ai.plugin

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections

object RuntimeLogStore {
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val lines = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_LINES = 2000

    fun append(message: String) {
        val normalized = message.trim()
        if (normalized.isBlank()) return
        synchronized(lines) {
            lines += "[${timeFormatter.format(Instant.now())}] $normalized"
            if (lines.size > MAX_LINES) {
                val toRemove = lines.size - MAX_LINES
                repeat(toRemove) { lines.removeAt(0) }
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
