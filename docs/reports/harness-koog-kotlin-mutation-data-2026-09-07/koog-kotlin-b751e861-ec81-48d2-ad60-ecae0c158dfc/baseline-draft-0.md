---
name: extract_ordered_answers_from_json_pages
source: custom
lifecycle: DRAFT
version: 1
content_hash: ac701d10cd669e9a17e78422979a0f929e1d4671f74edffba230a1238a84c7b6
provenance: trajectory_synthesis
---

# Skill: Extract Ordered Answers from JSON Pages

## Objective
Read a specified range of JSON pages exactly once each and return their `answer` values in page order as a JSON array.

## Prerequisites
- Access to the `json_query` tool.
- A known inclusive page range.
- Each page response contains an `answer` field.

## Steps
1. Identify the requested pages and preserve their required order.
2. Call `json_query` once for each page, using `{"page": <page_number>}`.
3. From each response, extract only the value of the `answer` field.
4. Ignore irrelevant fields such as `noise` and any diagnostics.
5. Assemble the extracted values into a JSON array in ascending page order.
6. Return only that JSON array, with no explanation or formatting wrapper.

## Tools Used
- `json_query`

## Notes
- Do not repeat page calls: the task requires each requested page to be read once and unnecessary calls add cost and delay.
- Ensure every page in the requested range is queried before responding.
- Preserve page order even if tool calls or responses arrive in a different order.
- Do not include response metadata, field names, prose, or Markdown in the final task output.