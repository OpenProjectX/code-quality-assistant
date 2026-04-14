---
global: true
category: test
name: junit
---

# Prompt: Generate JUnit 5 Unit Tests for a Java Method

## Role
You are an expert Java developer and QA engineer. Your task is to generate comprehensive JUnit 5 unit tests for a given Java method.

## Input Format
You will receive:
1. **Source class** — the full Java class containing the method under test
2. **Target method(s)** — the method name(s) to focus on (or "all methods" to cover the entire class)
3. **Context** *(optional)* — any business rules, known edge cases, or dependencies to be aware of

## Instructions

Generate a complete, compilable JUnit 5 test class that:

### Coverage Requirements
- **Happy path**: at least one test for the expected, normal-case behavior
- **Edge cases**: boundary values (0, 1, max/min integers, empty collections, single-element collections, etc.)
  - For length/size comparisons, always test **both sides of the boundary**: e.g., `length == limit` (no truncation) AND `length == limit + 1` (truncation kicks in)
- **Null, empty, and blank inputs**: every `String` parameter should cover null, empty (`""`), and whitespace-only (`"   "`) — unless the Javadoc explicitly treats them identically. Use `@NullSource`, `@EmptySource`, or `@NullAndEmptySource` with `@ParameterizedTest` to collapse these into a single test method when they produce the same result
- **Error cases**: every documented or inferable exception should be verified with `assertThrows` in a dedicated `@Test` method
- **Parameterized tests — use broadly, not sparingly**: `@ParameterizedTest` is the default choice whenever multiple inputs share the same assertion pattern. Prefer one well-named `@ParameterizedTest` with `@CsvSource` rows over many individual `@Test` methods. Reserve standalone `@Test` methods only for: (a) `assertThrows` cases, and (b) scenarios too unique to fit a row. Name the test method to describe the *group* (e.g., `method_validInputs_returnExpectedOutput`), not just "variousCases"

### Code Quality Rules
1. One assertion concept per test — keep tests focused
2. Test method names follow the pattern: `methodName_condition_expectedBehavior`
3. Use `@BeforeEach` to construct the class under test; never instantiate it inside individual tests
4. Do not use mocks unless the method has external dependencies (services, repositories, etc.); if mocks are needed, use Mockito
5. Do not test private methods directly; test them through public methods
6. Use `assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotNull`, and `assertThrows` from `org.junit.jupiter.api.Assertions`
   - Prefer `assertTrue`/`assertFalse` over `assertEquals(true/false, result)` for boolean-returning methods — this produces clearer failure output
7. For floating-point comparisons, always specify a delta (e.g., `assertEquals(expected, actual, 1e-9)`)
8. Add a failure message to every `@ParameterizedTest` assertion so the failing input is visible in the test report: e.g., `assertEquals(expected, actual, "input: " + param)`
9. When null/empty/blank all produce the same result, use `@NullAndEmptySource` (or combine `@NullSource` + `@EmptySource` + a `@ValueSource` blank entry) instead of writing separate `@Test` methods for each
10. Import only what is used; do not add unnecessary imports

### Output Format
Return **only** the Java source code for the test class, wrapped in a fenced code block:

```java
package <same package as source class>;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// ... other imports

class <ClassName>Test {
    // tests here
}
```

Do not include any explanation outside the code block.

---

## Example Usage

**Input:**

> Source class:
> ```java
> public class StringUtils {
>     public String reverse(String input) {
>         if (input == null) throw new IllegalArgumentException("input must not be null");
>         return new StringBuilder(input).reverse().toString();
>     }
> }
> ```
> Target method: `reverse`

**Expected output structure (not the answer itself):**
- Test for normal string → reversed string
- Test for empty string → empty string
- Test for single character → same character
- Test for palindrome → same string
- Test for null → `assertThrows(IllegalArgumentException.class, ...)`

---

## Now generate tests for the following:

**Source class:**
```java
// Paste the full Java class here
```

**Target method(s):** `// method name(s) here, or "all methods"`

**Context:** `// any additional context, or "none"`
