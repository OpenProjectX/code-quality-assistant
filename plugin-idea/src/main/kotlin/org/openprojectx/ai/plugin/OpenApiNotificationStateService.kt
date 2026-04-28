package org.openprojectx.ai.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class OpenApiNotificationStateService {

    private val states = ConcurrentHashMap<String, GenerationUiState>()

    fun getState(filePath: String): GenerationUiState =
        states[filePath] ?: GenerationUiState.Idle

    fun setState(filePath: String, state: GenerationUiState) {
        states[filePath] = state
    }

    fun clearState(filePath: String) {
        states.remove(filePath)
    }

    companion object {
        fun getInstance(project: Project): OpenApiNotificationStateService =
            project.service()
    }
}

sealed interface GenerationUiState {
    data object Idle : GenerationUiState
    data object Generating : GenerationUiState
    data object Done : GenerationUiState
    data object Dismissed : GenerationUiState
}
