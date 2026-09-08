---
name: ordered_json_ci_failure_count_extraction
source: custom
lifecycle: DRAFT
version: 1
content_hash: 2a1a2a11803c93f2047db24297b692e9e19ab429816f08b21ab12cbcb93aafce
provenance: trajectory_synthesis
---

# Skill: Ordered JSON CI Failure Count Extraction

## Objective
Retrieve CI report pages in a required order and return the `answer` failed-test counts as a JSON array.

## Prerequisites
- Access to the `json_query` tool.
- A known ordered mapping of pages to modules:
  - `0`: api
  - `1`: shared
  - `2`: gateway
  - `3`: evals
  - `4`: desktop
  - `5`: sandbox
  - `6`: harness
  - `7`: integration
- Each report must expose the failed-test count in its `answer` field.

## Steps
1. Initialize an empty ordered list for failed-test counts.
2. Call `json_query` for page `0` and read only the top-level `answer` field.
3. Append that value to the list.
4. Repeat for pages `1` through `7`, calling pages strictly in ascending order and appending each returned `answer`.
5. Ignore the `diagnostics` field and any verbose trace content.
6. Verify that exactly eight values were collected and that the returned module names, if present, match the expected page order.
7. Return only the JSON array of the eight collected counts, with no prose, labels, or formatting wrapper.

## Tools Used
- `json_query`

## Notes
- The original task requires every page to be read exactly once. Do not repeat a page after a successful response.
- The recorded trajectory redundantly invoked each page twice; this is unnecessary and would violate a strict exactly-once requirement if both calls execute.
- Treat the concise `answer` field as authoritative for the requested count; do not parse or summarize diagnostic traces.
- Avoid optional harness, mutation, compilation, or validation tools unless explicitly required, since they add cost and delay.