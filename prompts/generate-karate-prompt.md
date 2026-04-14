---
global: true
category: test
name: karate
---

# Prompt: Generate Karate Feature Files from OpenAPI Spec

## Instructions for the LLM

You are an expert in API testing with the Karate framework. Given an OpenAPI 3.x specification, generate Karate `.feature` files that thoroughly exercise each API endpoint — including happy paths, error cases, edge cases, data integrity assertions, and header validation.

**Rule priority**: If a **Custom Rule** (see the section at the bottom) conflicts with any built-in rule below, the custom rule always wins. Apply custom rules first, then fill in everything else with the built-in rules.

## Rules
### General Rules

1. **One feature file per resource** (group by the first path segment, e.g. `/todos` → `todos.feature`).
2. **`Background` block**: set `baseUrl` from `servers[0].url` and configure timeouts:
   ```
   * url 'https://...'
   * configure connectTimeout = 5000
   * configure readTimeout = 10000
   ```
3. **One `Scenario` per operation per test category** (happy path, error, edge case). Each scenario must be independently executable.
4. **Happy path** — assert `status 200`, then validate the full response schema using type markers (`#number`, `#string`, `#boolean`, `#notnull`, `#array`). Use `match each response` for array responses. For single-object responses, use a single `match response == { <full schema> }` expression rather than separate `match response.field ==` lines — this catches unexpected extra fields.
5. **Data integrity** — when an ID is used in the path, assert `response.id == <that id>` (exact value, not just `#number`). When a query filter is applied, first validate the full schema of each item with `match each response == { <full schema> }`, then assert `match each response contains { <field>: <value> }` to confirm every item matches the filter.
6. **Non-empty list** — for unfiltered list endpoints, assert `* assert response.length > 0` after the schema match.
7. **Empty list edge case** — supply a filter value very unlikely to exist (e.g. `userId=99999`) and assert `match response == []`.
8. **Error scenarios** — for each path parameter, generate:
   - A scenario with a non-existent numeric ID (e.g. `999999`) asserting `status 404`.
   - A scenario with an invalid type (e.g. `abc`) asserting `status 404`.
9. **Response header validation** — after every successful (2xx) response — including `200` and `201` — add a companion `Scenario` that asserts:
   ```
   And match responseHeaders['Content-Type'][0] contains 'application/json'
   ```
10. **Schema validation rules**: map OpenAPI types to Karate markers:
    - `integer` / `number` → `#number`
    - `string` → `#string`
    - `boolean` → `#boolean`
    - required / non-null field → `#notnull`
    - array → `#array`
11. **Request bodies** (POST/PUT): construct a minimal valid body from the schema `properties`. Also generate a scenario with missing required fields expecting `status 400` or `422`. Write these assertions to match the OpenAPI spec — if the target API is a fake/mock that ignores validation and returns `201` regardless, that is an API quirk, not a reason to omit the scenario.
12. **No external dependencies**: do not `call` other feature files; each scenario must be self-contained.
13. **Output format**: return only the raw `.feature` file content — no markdown fences, no explanation. If multiple feature files are needed, separate them with a line containing only `---` followed by the filename on the next line.

---

### Custom Rules (optional)

> **How to use**: Add your own rules here as a numbered list. Each custom rule overrides any built-in rule it conflicts with. Rules added here take the highest priority.
>
> **Format**: Follow the same style as the built-in rules above. Reference a built-in rule number if you are overriding it (e.g. "Override rule 9: …"), or just add a new numbered rule for entirely new behavior.

<!--
Example custom rules (delete this comment block and replace with your own):

C1. Override rule 9: Do NOT generate content-type header validation scenarios.
C2. Use `status 422` instead of `status 400` for missing required field scenarios.
C3. Add a `* print response` step after every `When method` call for debugging.
-->

```
<ADD YOUR CUSTOM RULES HERE — or delete this block if you have none>
```

---

## Example (for reference only — do not copy verbatim)

### Example Input

```yaml
openapi: 3.0.3
info:
  title: Posts API
  version: "1.0"
servers:
  - url: https://api.example.com
paths:
  /posts:
    get:
      summary: Get all posts
      parameters:
        - name: authorId
          in: query
          required: false
          schema:
            type: integer
      responses:
        "200":
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/Post'
  /posts/{id}:
    get:
      summary: Get a post by ID
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
      responses:
        "200":
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Post'
components:
  schemas:
    Post:
      type: object
      properties:
        id:
          type: integer
        authorId:
          type: integer
        title:
          type: string
        published:
          type: boolean
```

### Example Output

```
Feature: Posts API

  Background:
    * url 'https://api.example.com'
    * configure connectTimeout = 5000
    * configure readTimeout = 10000

  Scenario: Get all posts
    Given path '/posts'
    When method GET
    Then status 200
    And match response == '#array'
    And match each response == { id: '#number', authorId: '#number', title: '#string', published: '#boolean' }
    * assert response.length > 0

  Scenario: Get all posts — content-type header
    Given path '/posts'
    When method GET
    Then status 200
    And match responseHeaders['Content-Type'][0] contains 'application/json'

  Scenario: Get posts filtered by authorId=1
    Given path '/posts'
    And param authorId = 1
    When method GET
    Then status 200
    And match response == '#array'
    And match each response == { id: '#number', authorId: '#number', title: '#string', published: '#boolean' }
    And match each response contains { authorId: 1 }

  Scenario: Get posts filtered by unknown authorId
    Given path '/posts'
    And param authorId = 99999
    When method GET
    Then status 200
    And match response == []

  Scenario: Get a post by ID
    Given path '/posts/1'
    When method GET
    Then status 200
    And match response == { id: 1, authorId: '#number', title: '#string', published: '#boolean' }

  Scenario: Get a post by ID — content-type header
    Given path '/posts/1'
    When method GET
    Then status 200
    And match responseHeaders['Content-Type'][0] contains 'application/json'

  Scenario: Get a post — non-existent ID
    Given path '/posts/999999'
    When method GET
    Then status 404

  Scenario: Get a post — invalid ID type
    Given path '/posts/abc'
    When method GET
    Then status 404
```

---

## OpenAPI Spec (paste below)

```yaml
<PASTE YOUR OPENAPI SPEC HERE>
```
