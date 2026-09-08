---
name: ordered_json_ci_failure_count_extraction
source: custom
lifecycle: DRAFT
version: 1
content_hash: 080033a4a5eda1f94022e811cd597d6a5aa9c1f88d077634b1a69210fa76f6f6
provenance: trajectory_synthesis
---

# Skill: Ordered JSON CI Failure Count Extraction

## Objective
Retrieve eight CI report pages in a required order and return the `answer` failed-test counts as a JSON array.

## Prerequisites
- Access to the `json_query` tool.
- Pages `0` through `7` must correspond, in order, to: `api`, `shared`, `gateway`, `evals`, `desktop`, `sandbox`, `harness`, and `integration`.
- Each report must contain the failed-test count in its top-level `answer` field.

## Steps
1. Create an ordered page plan: `0, 1, 2, 3, 4, 5, 6, 7`.
2. Call `json_query` exactly once for each page, sequentially:
   1. `json_query({"page":0})`
   2. `json_query({"page":1})`
   3. `json_query({"page":2})`
   4. `json_query({"page":3})`
   5. `json_query({"page":4})`
   6. `json_query({"page":5})`
   7. `json_query({"page":6})`
   8. `json_query({"page":7})`
3. From each returned JSON object, read only the top-level `answer` value.
4. Ignore the `diagnostics` field and any verbose trace content.
5. Preserve the required module/page order when collecting values.
6. Return only a valid JSON array containing the eight counts, with no prose, labels, or Markdown.

## Tools Used
- `json_query`

## Notes
- Do not repeat a page query: the task may require each page to be read exactly once.
- Do not query pages out of order, even if results could theoretically be gathered independently.
- Verify the returned `module` matches the expected page-to-module mapping when available, but do not perform extra calls solely for verification.
- Avoid optional harness, mutation, compilation, or validation tools unless explicitly necessary; they add cost and delay.