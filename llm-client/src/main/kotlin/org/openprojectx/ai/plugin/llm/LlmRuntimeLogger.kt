package org.openprojectx.ai.plugin.llm

object LlmRuntimeLogger {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun info(message: String) {
        sink?.invoke("LLM | INFO | $message")
    }

    fun error(message: String) {
        sink?.invoke("LLM | ERROR | $message")
    }
}
