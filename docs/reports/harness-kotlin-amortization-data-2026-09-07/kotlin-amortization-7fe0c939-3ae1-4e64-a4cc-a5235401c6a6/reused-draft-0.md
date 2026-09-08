---
name: sequential_ci_failure_count_extraction
source: custom
lifecycle: DRAFT
version: 1
content_hash: c9c75c4fd0c6d97071bb8d289675db1e566b1a63f3d25168cf2ce4a0ada7459b
provenance: trajectory_synthesis
---

# Skill: Sequential CI Failure Count Extraction

## Objective
Retrieve failed-test counts from eight ordered JSON CI reports and return them as a JSON array in module order.

## Prerequisites
- Access to the `json_query` tool.
- Reports available at pages `0` through `7`.
- Required module/page order: `api`, `shared`, `gateway`, `evals`, `desktop`, `sandbox`, `harness`, `integration`.

## Steps
1. Initialize an empty list for eight failed-test counts.
2. Call `json_query` once for page `0` and extract the `failedTests` value from the answer field.
3. Call `json_query` once for page `1` and extract the count.
4. Continue sequentially through pages `2`, `3`, `4`, `5`, `6`, and `7`, making exactly one call per page and recording each answer-field count.
5. Ignore diagnostic trace text and harness metadata; use only the returned answer value containing the failed-test count.
6. Preserve page order when assembling the results.
7. Return only a compact JSON array of the eight counts, with no labels, explanation, or Markdown fencing.

## Tools Used
- `json_query`

## Notes
- Do not repeat page requests: each page must be read exactly once.
- Avoid optional harness, mutation, compilation, or validation tools unless explicitly required, as they add unnecessary cost and delay.
- The observed successful run produced counts `[3,0,7,1,0,4,2,0]`; treat this as trajectory-specific data rather than a general fixed result.