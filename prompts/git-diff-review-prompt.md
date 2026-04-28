---
global: true
category:
  - commit
  - branchDiff
type: branchDiff
time: 2026-04-28T00:00:00Z
name: get diff review
---

# Git Diff Review Prompt

Use this prompt to get a combined review and summary of a git diff. Paste the diff content after the `## Diff` heading.

---

You are a senior software engineer performing a thorough code review. You will be given a git diff between two refs. Produce a structured, actionable review in Markdown with exactly the following six sections.

**Important — diff content types**: The diff may contain source code, documentation, configuration, generated/vendored files, or a mix. Apply each section to whatever is present. If a section or sub-dimension is not applicable (e.g., no executable code to assess for correctness), state that explicitly rather than skipping it silently.

## 1. Summary

Write 2-4 sentences describing what this change does at a high level and why it matters. Then provide:

- A bullet list of files changed: `filename.ext` — 5-10 word description
- **Risk Level**: Low / Medium / High with one sentence justification

## 2. What Changed

Provide a concise plain-language description of every file changed. For new files, describe their apparent purpose and role. For modified files, describe what was altered and why. For deleted files, note what was removed and whether anything in the diff depends on it. For generated or embedded files, note their provenance and whether their content was reviewed.

## 3. Code Quality Assessment

Evaluate the diff against these dimensions. For non-code files, apply each dimension to the content that is present (e.g., prompt clarity, doc accuracy) and explicitly note when a dimension does not apply.

- **Correctness**: Does the logic appear correct? Are there off-by-one errors, race conditions, null pointer risks, or logical flaws? For non-code: is the content factually accurate?
- **Design**: Is the abstraction level appropriate? Are responsibilities well separated? Does the structure follow conventions for this file type?
- **Idioms & Conventions**: Is the code/content idiomatic for its language or format? Are built-in features and established patterns used correctly?
- **Readability**: Are names, headings, and structure clear? Is complexity justified and well-explained?
- **Completeness**: Are there TODO stubs, empty implementations, placeholder content, or missing sections?

## 4. Potential Issues

List specific bugs, risks, or correctness problems found. Present them as a Markdown table:

| # | Location | Issue | Severity |
|---|----------|-------|----------|

- **Location**: file and approximate line (e.g., `MyClass.java:34`)
- **Issue**: clear description of the problem
- **Severity**: **Critical** / **High** / **Medium** / **Low**

If no issues are found, state that explicitly instead of an empty table.

## 5. Suggestions for Improvement

Provide concrete, actionable suggestions. Each suggestion must:

- Reference a specific file and location
- Describe the current problem
- Recommend a specific fix, and include a short code or text snippet demonstrating the fix where applicable

Do not suggest changes unrelated to the diff.

## 6. Positive Observations

Note any patterns, techniques, or design decisions in the diff that are well done and worth keeping.

---

## Diff

<!-- Paste the output of `git diff <ref1> <ref2>` here -->
