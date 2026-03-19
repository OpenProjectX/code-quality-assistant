package org.openprojectx.ai.plugin.core


object PromptBuilder {
    const val DEFAULT_REST_ASSURED_RULES = """
        Target: Java tests using JUnit 5 + Rest Assured.
        Requirements:
        - Generate a single test class: {{qualifiedClassName}}
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
    """

    const val DEFAULT_KARATE_RULES = """
        Target: Karate tests.
        Requirements:
        - Generate feature file content.
        - Cover happy path + negative cases per operation similar to above.
        - Base URL: use {{karateBaseUrl}}.
        - Output ONLY code/content, no markdown.
        Karate syntax rules:
    """

    const val DEFAULT_WRAPPER_TEMPLATE = """
        You are a senior SDET. Generate high-quality automated API tests from the contract below.

        Contract type: {{contractType}}
        Base URL hint: {{baseUrlHint}}

        Additional user notes:
        {{outputNotes}}

        {{frameworkRules}}

        CONTRACT (verbatim):
        {{contractText}}
    """

    fun build(req: GenerationRequest, template: GenerationPromptTemplate = GenerationPromptTemplate()): String {
        val frameworkRules = when (req.framework) {
            Framework.REST_ASSURED -> {
                val packageName = req.packageName?.takeIf { it.isNotBlank() }
                    ?: error("packageName is required for REST_ASSURED generation")

                render(template.restAssuredRules, mapOf(
                    "qualifiedClassName" to "$packageName.${req.className}"
                ))
            }

            Framework.KARATE -> render(template.karateRules, mapOf(
                "karateBaseUrl" to (req.baseUrl ?: "karate.properties['api.baseUrl'] || 'http://localhost:8080'")
            ))
        }

        return render(template.wrapper, mapOf(
            "contractType" to "OpenAPI",
            "baseUrlHint" to (req.baseUrl ?: "not provided"),
            "outputNotes" to (req.outputNotes ?: "(none)"),
            "frameworkRules" to frameworkRules,
            "contractText" to req.contractText
        ))
    }

    private fun render(template: String, variables: Map<String, String>): String {
        var result = template.trimIndent()
        variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
