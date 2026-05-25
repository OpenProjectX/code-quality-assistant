package org.openprojectx.ai.plugin.core


object PromptBuilder {
    const val DEFAULT_REST_ASSURED_RULES = """
        Target: Java tests using JUnit 5 + Rest Assured.
        Requirements:
        - Generate a single test class: {{qualifiedClassName}}
        - Include one executable helper method (for local run) that can run this class's tests.
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

    const val DEFAULT_JAVA_METHOD_RULES = """
        Target: Java unit/integration tests for the provided Java source.
        Requirements:
        - Generate a single JUnit 5 test class: {{qualifiedClassName}}.
        - Include one executable helper method (for local run) that can run this class's tests.
        - Focus only on methods present in the provided source; do not invent methods.
        - Prioritize the method referenced by user notes when provided.
        - For each tested method, include meaningful assertions for behavior and edge cases.
        - Mock external collaborators (HTTP/DB/remote dependencies) with Mockito or test doubles; do not call real external systems.
        - Keep tests deterministic and runnable.
        - Output ONLY Java code, no markdown.
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
        val frameworkRules = when (req.contractType) {
            ContractType.JAVA -> {
                val packageName = req.packageName?.takeIf { it.isNotBlank() }
                    ?: error("packageName is required for JAVA generation")
                render(DEFAULT_JAVA_METHOD_RULES, mapOf(
                    "qualifiedClassName" to "$packageName.${req.className}"
                ))
            }
            ContractType.OPENAPI -> when (req.framework) {
                Framework.REST_ASSURED -> {
                    val packageName = req.packageName?.takeIf { it.isNotBlank() }
                        ?: error("packageName is required for REST_ASSURED generation")

                    val apiRules = template.restAssuredRules + "\n- Mock API results/dependencies where appropriate; avoid unstable real upstream calls."
                    render(apiRules, mapOf(
                        "qualifiedClassName" to "$packageName.${req.className}"
                    ))
                }

                Framework.KARATE -> render(template.karateRules + "\n- Prefer deterministic mocking/stubbing for external dependencies when possible.", mapOf(
                    "karateBaseUrl" to (req.baseUrl ?: "karate.properties['api.baseUrl'] || 'http://localhost:8080'")
                ))
            }
        }

        return render(template.wrapper, mapOf(
            "contractType" to when (req.contractType) {
                ContractType.OPENAPI -> "OpenAPI/REST API"
                ContractType.JAVA -> "Java Source Methods"
            },
            "baseUrlHint" to (req.baseUrl ?: "not provided"),
            "outputNotes" to (req.outputNotes ?: "(none)"),
            "frameworkRules" to frameworkRules,
            "contractText" to req.contractText
        ))
    }

    fun buildTestForUncoveredFile(
        filePath: String,
        fileName: String,
        sourceCode: String,
        uncoveredLines: Int,
        coverage: Double?,
        generationTemplate: GenerationPromptTemplate
    ): String {
        val coverageText = coverage?.let { String.format("%.2f%%", it) } ?: "unknown"
        val packageHint = sourceCode.lines()
            .firstOrNull { it.trimStart().startsWith("package ") }
            ?.trim()?.removeSuffix(";")?.removePrefix("package ")
        val qualifiedClassName = buildString {
            if (packageHint != null) append(packageHint).append('.')
            append(fileName.removeSuffix(".java").removeSuffix(".kt"))
        }

        val rules = generationTemplate.restAssuredRules.ifBlank { DEFAULT_JAVA_METHOD_RULES }
        val populatedRules = render(rules, mapOf("qualifiedClassName" to qualifiedClassName))

        return render(generationTemplate.wrapper.ifBlank { DEFAULT_WRAPPER_TEMPLATE }, mapOf(
            "contractType" to "Java Source Methods",
            "baseUrlHint" to "not applicable",
            "outputNotes" to "File: $filePath\nCurrent coverage: $coverageText\nUncovered lines: $uncoveredLines\nGenerate tests to improve coverage of uncovered code paths.",
            "frameworkRules" to populatedRules,
            "contractText" to sourceCode
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
