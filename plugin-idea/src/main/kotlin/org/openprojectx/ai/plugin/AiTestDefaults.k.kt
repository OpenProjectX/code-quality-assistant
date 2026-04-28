package org.openprojectx.ai.plugin

import org.openprojectx.ai.plugin.core.Framework

object AiTestDefaults {
    val DEFAULT_FRAMEWORK: Framework = Framework.REST_ASSURED
    const val DEFAULT_CLASS_NAME: String = "OpenApiGeneratedTests"
    const val DEFAULT_BASE_URL: String = ""
    const val DEFAULT_NOTES: String = ""

    fun defaultLocation(framework: Framework): String = when (framework) {
        Framework.REST_ASSURED -> "src/test/java"
        Framework.KARATE -> "src/test/resources/karate"
    }

    fun defaultPackageName(framework: Framework): String? = when (framework) {
        Framework.REST_ASSURED -> "org.openprojectx.ai.plugin.generated"
        Framework.KARATE -> null
    }
}
