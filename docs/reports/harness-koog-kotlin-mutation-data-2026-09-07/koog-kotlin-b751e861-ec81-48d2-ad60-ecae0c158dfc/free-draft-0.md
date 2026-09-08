---
name: extract_ordered_page_answers_via_json_query
source: custom
lifecycle: DRAFT
version: 1
content_hash: d26e5db94dbc01a6afd6d92625a747078ce53c982ac9fc30087a1f00157fdd8a
provenance: trajectory_synthesis
---

# Skill: Extract Ordered Page Answers via JSON Query

## Objective
Retrieve the `answer` value from each specified page using `json_query` and return them as a JSON array in page order.

## Prerequisites
- Access to the `json_query` tool.
- A known inclusive page range to read.
- Tool responses containing an `answer` field.

## Steps
1. Call `json_query` with the first requested page number, for example `{"page":0}`.
2. Ignore irrelevant fields such as `noise` and record only the value of `answer`.
3. Repeat the query exactly once for each remaining requested page, incrementing the page number in order.
4. Maintain the recorded answer values in the same order as the page numbers.
5. Return only a valid JSON array containing the recorded values, with no explanation or extra formatting.

## Tools Used
- `json_query`

## Notes
- Read every requested page; do not infer or skip values.
- Extract only the `answer` field and disregard diagnostic or noise fields.
- Preserve page order exactly, including leading page 0 when requested.
- Avoid optional harness experiments or processor changes unless necessary, as they can add cost and delay.
- Ensure the final response is raw JSON, such as `[6284,1739,8452,3906]`.