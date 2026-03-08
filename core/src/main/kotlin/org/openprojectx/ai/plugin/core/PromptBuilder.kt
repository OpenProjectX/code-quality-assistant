package org.openprojectx.ai.plugin.core


object PromptBuilder {
    fun build(req: GenerationRequest): String {
        val frameworkRules = when (req.framework) {
            Framework.REST_ASSURED -> {
                val packageName = req.packageName?.takeIf { it.isNotBlank() }
                    ?: error("packageName is required for REST_ASSURED generation")

                """
        Target: Java tests using JUnit 5 + Rest Assured.
        Requirements:
        - Generate a single test class: $packageName.${req.className}
        - Use RestAssured given/when/then style.
        - Add assertions beyond status code: validate key fields, required properties, enums, and error models if present.
        - For each operation in the spec, generate:
          1) happy path test
          2) missing required field test
          3) invalid enum/type/boundary test (pick one meaningful invalid case)
        - Reuse common setup in @BeforeAll or a base method.
        - If baseUrl provided, use it; otherwise read from system property "api.baseUrl" with a default of "http://localhost:8080".
        - Do NOT invent endpoints not in the contract.
        - Output ONLY code, no markdown.
      """.trimIndent()
            }

            Framework.KARATE -> """
        Target: Karate tests.
        Requirements:
        - Generate feature file content.
        - Cover happy path + negative cases per operation similar to above.
        - Base URL: use ${req.baseUrl ?: "karate.properties['api.baseUrl'] || 'http://localhost:8080'"}.
        - Output ONLY code/content, no markdown.
        Karate syntax rules:
      """.trimIndent()
        }

        val baseUrlHint = req.baseUrl?.let { "Base URL hint: $it" } ?: "Base URL hint: not provided"

        return """
      You are a senior SDET. Generate high-quality automated API tests from the OpenAPI contract below.

      $baseUrlHint

      Additional user notes:
      ${req.outputNotes ?: "(none)"}

      $frameworkRules

      CONTRACT (verbatim):
      ${req.contractText}
    """.trimIndent()
    }
}