# AI Test Generator Plugin

<div align="center">

[![IntelliJ Platform](https://img.shields.io/badge/Platform-IntelliJ%20IDEA%202025%2B-brightgreen)](https://www.jetbrains.com/idea/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)
[![Version](https://img.shields.io/badge/Version-0.1.3-orange)](https://github.com/OpenProjectX/ai-test-plugin/releases)

</div>

An IntelliJ IDEA plugin that empowers **Test-Driven Development (TDD)** and **contract-based testing** using Large Language Models. Automatically generate high-quality API tests from OpenAPI specifications.

## Features

- **Contract-Based Test Generation** - Generate tests directly from OpenAPI (Swagger) specs
- **Multi-Framework Support** - Generate tests in JUnit 5 + Rest Assured or Karate DSL
- **AI-Powered** - Leverages any OpenAI-compatible LLM endpoint
- **Intelligent Test Coverage** - Generates happy path + negative test cases
- **IDE Integration** - Non-intrusive notification banner on OpenAPI files

## Supported Frameworks

| Framework | Description |
|-----------|-------------|
| **JUnit 5 + Rest Assured** | Java-based REST API testing with fluent assertions |
| **Karate DSL** | BDD-style API test automation |

## Architecture

```
ai-test-plugin/
├── core/                  # Core domain models & prompt building
│   └── PromptBuilder.kt   # LLM prompt construction
├── llm-client/            # OpenAI-compatible LLM client
│   └── OpenAiCompatibleProvider.kt
└── plugin-idea/           # IntelliJ IDEA plugin
    ├── GenerateTestsDialog.kt    # UI configuration
    ├── GenerateTestsService.kt  # Test generation orchestration
    └── OpenApiEditorNotificationProvider.kt
```

## Installation

### From JetBrains Marketplace

1. Open IntelliJ IDEA 2025.2+
2. Go to **Settings → Plugins**
3. Search for "AI Test Generator"
4. Install and restart IDE

### Manual Installation

Download the latest release from [GitHub Releases](https://github.com/OpenProjectX/ai-test-plugin/releases) and install via **Settings → Plugins → Install Plugin from Disk**.

## Configuration

### LLM Settings

Create or edit `ai-test-config.yaml` in your project root:

```yaml
llm:
  endpoint: "https://api.openai.com/v1/chat/completions"
  apiKey: "${OPENAI_API_KEY}"
  model: "gpt-4o"

generation:
  defaultFramework: "restassured"
  defaultClassName: "ApiTest"
  defaultBaseUrl: "http://localhost:8080"
  defaults:
    restassured:
      packageName: "com.example.tests"
      location: "src/test/java"
    karate:
      location: "src/test/kotlin"
```

### Environment Variables

- `OPENAI_API_KEY` - Your OpenAI API key (or any OpenAI-compatible provider)
- Alternatively, use `${ENV_VAR}` syntax in config

## Usage

1. Open any `.yaml` or `.json` OpenAPI specification file
2. A notification banner appears: **"Generate Tests?"**
3. Click to open the generation dialog
4. Configure:
   - **Framework** - JUnit 5 + Rest Assured or Karate DSL
   - **Location** - Output directory
   - **Class Name** - Test class/feature name
   - **Base URL** - Optional API base URL
   - **Extra Instructions** - Additional context for the LLM
5. Click **Generate**

The plugin generates comprehensive test coverage including:

- **Happy path tests** for each endpoint
- **Negative tests** for missing required fields
- **Boundary/type validation** tests

## Development

### Prerequisites

- IntelliJ IDEA 2025.2+ (for development)
- JDK 17+
- Gradle 9.x

### Build

```bash
# Build the plugin JAR
./gradlew :plugin-idea:buildPlugin

# Build with ZIP distribution
./gradlew :plugin-idea:assemble

# Run in development IDE
./gradlew :plugin-idea:runIde
```

### Publish

```bash
# Publish to Maven Local
./gradlew :plugin-idea:publishPluginZipPublicationToMavenLocal

# Publish to Sonatype (requires credentials)
./gradlew :plugin-idea:publishPluginDistributionPublicationToSonatypeRepository closeAndReleaseSonatypeStagingRepository
```

## Test Frameworks Output

### Rest Assured Example

```java
package com.example.tests;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

public class UserApiTest {
    
    @BeforeAll
    public static void setup() {
        RestAssured.baseURI = "http://localhost:8080";
    }

    @Test
    void getUser_Success() {
        given()
            .pathParam("id", 1)
        .when()
            .get("/users/{id}")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("id", equalTo(1))
            .body("name", notNullValue());
    }
}
```

### Karate DSL Example

```gherkin
Feature: User API

  Background:
    * url baseUrl

  Scenario: Get User - Success
    Given path 'users', 1
    When method get
    Then status 200
    And match response == { id: 1, name: '#notnull' }
```

## Contributing

Contributions are welcome! Please read our contributing guidelines before submitting PRs.

## License

Licensed under the Apache License 2.0 - see [LICENSE](LICENSE) for details.
