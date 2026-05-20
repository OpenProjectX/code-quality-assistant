package org.openprojectx.ai.plugin

object SonarQubeMockData {

    private val now: String
        get() = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private val sampleBase = "plugin-idea/src/main/java/org/openprojectx/ai/plugin/samples"
    private val sampleApi = "$sampleBase/ApiAndJavaMixedSample.java"
    private val sampleCommon = "$sampleBase/CommonJavaMethods.java"

    internal fun coverageReport(projectKey: String, targetCoverage: Double): SonarQubeCoverageReport {
        val allFiles = listOf(
            SonarQubeFileCoverage(
                key = "$projectKey:$sampleApi",
                path = sampleApi,
                name = "ApiAndJavaMixedSample.java",
                coverage = 42.5,
                uncoveredLines = 44
            ),
            SonarQubeFileCoverage(
                key = "$projectKey:$sampleCommon",
                path = sampleCommon,
                name = "CommonJavaMethods.java",
                coverage = 38.1,
                uncoveredLines = 49
            )
        )

        val filteredFiles = allFiles
            .filter { (it.uncoveredLines ?: 0) > 0 || (it.coverage ?: 100.0) < targetCoverage }

        return SonarQubeCoverageReport(
            projectKey = projectKey,
            projectCoverage = 40.3,
            projectLineCoverage = 40.3,
            projectBranchCoverage = 0.0,
            uncoveredLines = 93,
            files = filteredFiles
        )
    }

    internal fun scanResult(projectKey: String, serverUrl: String): SonarCubeResult {
        val issues = listOf(
            SonarCubeIssue(
                key = "MOCK-AY8q001",
                path = sampleApi,
                line = 30,
                severity = "CRITICAL",
                type = "BUG",
                rule = "java:S112",
                message = "Generic exceptions (IOException, InterruptedException) should not be thrown from this method signature."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q002",
                path = sampleApi,
                line = 32,
                severity = "MAJOR",
                type = "CODE_SMELL",
                rule = "java:S107",
                message = "URI constructed via string concatenation; use URI builder or parameterized approach."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q003",
                path = sampleApi,
                line = 41,
                severity = "CRITICAL",
                type = "VULNERABILITY",
                rule = "java:S2755",
                message = "HTTP response body is returned directly without validation — may expose the caller to injection."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q004",
                path = sampleApi,
                line = 58,
                severity = "MAJOR",
                type = "CODE_SMELL",
                rule = "java:S2259",
                message = "Null check for values is at the collection level, but individual null elements inside the loop are handled — consider using Stream.filter(Objects::nonNull)."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q005",
                path = sampleApi,
                line = 66,
                severity = "MAJOR",
                type = "CODE_SMELL",
                rule = "java:S1168",
                message = "Returning an empty string for null input may hide errors; consider returning Optional<String> or throwing a checked exception."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q006",
                path = sampleApi,
                line = 73,
                severity = "MINOR",
                type = "CODE_SMELL",
                rule = "java:S109",
                message = "Magic number 900 should be extracted to a named constant."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q007",
                path = sampleApi,
                line = 53,
                severity = "BLOCKER",
                type = "BUG",
                rule = "java:S1166",
                message = "Empty collection check re-invents the wheel; use Collection.isEmpty()."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q101",
                path = sampleCommon,
                line = 25,
                severity = "MAJOR",
                type = "CODE_SMELL",
                rule = "java:S112",
                message = "Throwing IllegalArgumentException from a method is a code smell; consider a custom checked exception."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q102",
                path = sampleCommon,
                line = 54,
                severity = "MAJOR",
                type = "CODE_SMELL",
                rule = "java:S5361",
                message = "replaceAll with regex \\s+ is called inside isPalindrome; pre-compile the Pattern for performance."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q103",
                path = sampleCommon,
                line = 58,
                severity = "MAJOR",
                type = "CODE_SMELL",
                rule = "java:S1192",
                message = "The string replacement and reverse logic is duplicated inside isPalindrome and reverse methods."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q104",
                path = sampleCommon,
                line = 35,
                severity = "BLOCKER",
                type = "BUG",
                rule = "java:S1168",
                message = "reverse() returns null for null input — callers may NPE. Return Optional<String> or an empty string."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q105",
                path = sampleCommon,
                line = 42,
                severity = "CRITICAL",
                type = "BUG",
                rule = "java:S2259",
                message = "factorial with large n can overflow long without warning; use Math.multiplyExact or BigInteger."
            ),
            SonarCubeIssue(
                key = "MOCK-AY8q106",
                path = sampleCommon,
                line = 76,
                severity = "MINOR",
                type = "CODE_SMELL",
                rule = "java:S1104",
                message = "safeTrim delegates to Objects.requireNonNullElse — consider adding null-safety annotations for better static analysis."
            )
        )

        return SonarCubeResult(
            projectKey = projectKey,
            serverUrl = serverUrl,
            coverage = 40.3,
            lineCoverage = 40.3,
            branchCoverage = 0.0,
            uncoveredLines = 93,
            bugs = 4,
            vulnerabilities = 1,
            codeSmells = 8,
            issues = issues,
            reportTimestamp = now
        )
    }
}
