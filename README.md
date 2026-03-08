# AI Test Generator Plugin

<div align="center">

[![IntelliJ Platform](https://img.shields.io/badge/Platform-IntelliJ%20IDEA%202025%2B-brightgreen)](https://www.jetbrains.com/idea/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)
[![Version](https://img.shields.io/badge/Version-0.1.3-orange)](https://github.com/OpenProjectX/ai-test-plugin/releases)

*Contract-driven test generation powered by AI*

</div>

## The Contract is the Source of Truth

This plugin embraces **Contract-Based Testing** as a first-class paradigm. The contract defines the expected behavior of your service — and tests should derive from it automatically.

### Supported Contract Types

| Contract Type | Status | Description |
|---------------|--------|-------------|
| **OpenAPI / Swagger** | ✅ Stable | REST API specifications (YAML/JSON) |
| **gRPC Protobuf** | 🔜 Planned | Protocol Buffer service definitions |
| **Java Interface** | 🔜 Planned | Java class/interface contracts |
| **GraphQL Schema** | 🔜 Future | GraphQL type definitions |

> *The plugin is architected to be extensible — adding a new contract type requires implementing a new parser and prompt template.*

## Features

- **Contract-First Testing** — Tests are generated from the contract, not the implementation
- **Multi-Framework Support** — JUnit 5 + Rest Assured or Karate DSL
- **AI-Powered** — Leverages any OpenAI-compatible LLM endpoint
- **Comprehensive Coverage** — Generates happy path + negative test cases
- **IDE Integration** — Seamless notification banner on contract files

## Why Contract-Based Testing?

```
┌─────────────────────────────────────────────────────────────┐
│                      CONTRACT                               │
│                  (OpenAPI / Protobuf / ...)                 │
│                        ⭐ SOURCE OF TRUTH                   │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│              AI Test Generator Plugin                       │
│         (context-aware prompt engineering)                  │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                    TESTS                                    │
│        Rest Assured / Karate / JUnit / Spock ...            │
└─────────────────────────────────────────────────────────────┘
```

The contract is:
- **Single source of truth** — No duplication between spec and tests
- **Version-controlled** — Contract changes trigger test regeneration
- **Language-agnostic** — Works with any test framework

## Architecture

```
ai-test-plugin/
├── core/                  # Contract-agnostic core
│   ├── GenerationRequest.kt
│   ├── PromptBuilder.kt   # LLM prompt construction
│   └── Framework.kt       # Test framework abstractions
├── llm-client/            # OpenAI-compatible LLM client
│   └── OpenAiCompatibleProvider.kt
└── plugin-idea/           # IntelliJ IDEA plugin
    ├── GenerateTestsDialog.kt    # UI configuration
    ├── GenerateTestsService.kt  # Test generation orchestration
    └── contracts/               # Contract type handlers
        └── openapi/             # OpenAPI parser & heuristics
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
- Use `${ENV_VAR}` syntax in config for secrets

## Usage

1. Open any contract file (`.yaml`, `.json` for OpenAPI)
2. A notification banner appears: **"Generate Tests?"**
3. Click to open the generation dialog
4. Configure:
   - **Framework** - JUnit 5 + Rest Assured or Karate DSL
   - **Location** - Output directory
   - **Class Name** - Test class/feature name
   - **Base URL** - Optional API base URL hint
   - **Extra Instructions** - Additional context for the LLM
5. Click **Generate**

The plugin analyzes the contract and generates:

- **Happy path tests** for each operation/endpoint
- **Negative tests** for missing required fields
- **Boundary/type validation** tests

### Rest Assured Example (from OpenAPI)

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

    @Test
    void getUser_NotFound() {
        given()
            .pathParam("id", 99999)
        .when()
            .get("/users/{id}")
        .then()
            .statusCode(404);
    }
}
```

### Karate DSL Example

```gherkin
Feature: User API Contract Tests

  Background:
    * url baseUrl || 'http://localhost:8080'

  Scenario: Get user - success
    Given path 'users', 1
    When method get
    Then status 200
    And match response == { id: 1, name: '#notnull', email: '#regex[.*@.*]' }

  Scenario: Get user - not found
    Given path 'users', 99999
    When method get
    Then status 404
    And match response == { code: '#notnull', message: '#string' }
```

## Development

### Prerequisites

- IntelliJ IDEA 2025.2+ (for development)
- JDK 17+
- Gradle 9.x

### Build Commands

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

## Roadmap

- [ ] **gRPC Protobuf support** — Generate tests from `.proto` files
- [ ] **Java Interface contracts** — Generate tests from annotated interfaces
- [ ] **Contract diff detection** — Alert when contract changes affect existing tests
- [ ] **Test regeneration** — Smart update instead of full overwrite

## Contributing

Contributions are welcome! Key areas:
- New contract type parsers (gRPC, GraphQL, Java)
- Additional test framework generators (Spock, TestNG)
- LLM prompt optimization

## License

Licensed under the Apache License 2.0 - see [LICENSE](LICENSE) for details.

---

*Built with ❤️ for the contract-driven testing community*
