package org.openprojectx.ai.plugin

sealed interface FrameworkUiConfig {
    data object None : FrameworkUiConfig
    data class RestAssured(val packageName: String) : FrameworkUiConfig
}