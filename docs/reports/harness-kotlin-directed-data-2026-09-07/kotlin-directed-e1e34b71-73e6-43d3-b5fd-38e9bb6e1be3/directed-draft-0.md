---
name: kotlin_harness_processor_lifecycle
source: custom
lifecycle: DRAFT
version: 1
content_hash: 5061fc000168b072d9a13dbef32aa4c0e5f3055df0314d9f50fb5119bf5a19b9
provenance: trajectory_synthesis
---

# Skill: Kotlin Harness Processor Lifecycle

## Objective
Create, validate, activate, and use a Kotlin `json_query` observation processor that extracts answer values while preserving unknown formats.

## Prerequisites
- Access to the `harness_inspect`, `harness_propose`, `harness_evaluate`, and `harness_activate` tools.
- Access to the target observation tool, `json_query`.
- A Kotlin `.kts` processor environment with `kotlinx.serialization.json.*` available.
- No network or file operations.

## Steps
1. Inspect the harness for `json_query` using `harness_inspect`.
2. Read the processor contract, language requirements, active revision status, and validation input/output examples.
3. Write a Kotlin script whose final expression is a `String` and which:
   - Reads the raw value from `observation.text`.
   - Attempts to parse JSON and, when the parsed root is an object containing `answer`, returns that field’s primitive content.
   - Falls back to extracting `answer=<value>` from delimited text.
   - Falls back to extracting the `answer` column from a simple CSV header/data pair.
   - Returns the complete original raw text for unknown or unsupported formats.
4. Propose the source through `harness_propose` with `toolName: "json_query"` and `baseRevision: null`.
5. Save the returned revision ID.
6. Evaluate the proposed revision with `harness_evaluate`.
7. Confirm that evaluation reports `passed=true`.
8. Activate the validated revision with `harness_activate`.
9. Account for deferred activation: the revision becomes active on the next tool invocation.
10. Call `json_query` for each required page exactly once, in requested page order.
11. Return only a JSON array containing the transformed answer values in page order.

## Tools Used
- `harness_inspect`
- `harness_propose`
- `harness_evaluate`
- `harness_activate`
- `json_query`

## Notes
- Inspect before proposing changes; validation examples define expected extraction behavior.
- The Kotlin script must end with a `String` expression.
- Use `Json.parseToJsonElement(raw) as? JsonObject` so non-object JSON is treated as unknown and preserved.
- For JSON primitive answers, use `JsonPrimitive.content` to return unquoted string values and numeric text.
- Do not alter original tool provenance or status metadata.
- Activation may report as pending and takes effect at the next observation-tool call.
- Harness diagnostic lines are presentation metadata; use the transformed observation value, not raw artifact references.
- Do not pre-read original pages before activation when the task requires transformed reads through the active processor.