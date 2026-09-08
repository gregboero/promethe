---
name: retrieve_ordered_answers_from_json_pages
source: custom
lifecycle: DRAFT
version: 1
content_hash: 77d9fd5bb42bb2da1ad1fa81c3f11d1d36b88978de6c68daf869b62ca1b2a288
provenance: trajectory_synthesis
---

# Skill: Retrieve Ordered Answers from JSON Pages

## Objective
Read a specified range of JSON pages exactly once each and return their `answer` values in page order as a JSON array.

## Prerequisites
- Access to a `json_query` tool that accepts a page identifier, such as `{"page": 0}`.
- A known inclusive page range.
- Each page response contains an `answer` field.

## Steps
1. Identify the requested page range and preserve its ascending order.
2. Call `json_query` once for each page in the range, beginning with the first page.
3. From each response, extract only the value of the `answer` field.
4. Ignore unrelated fields such as `noise`, diagnostics, or metadata.
5. Store each extracted answer in a list at the position corresponding to its page.
6. After all pages have been read, return only the completed JSON array, with no explanation or additional text.

## Tools Used
- `json_query`

## Notes
- Do not query a page more than once unless the tool call failed or returned no usable result; duplicate calls add unnecessary cost and delay.
- Maintain strict page order rather than sorting answers or using response timing.
- Verify that every requested page was queried before responding.
- When the required output format is a JSON array, avoid Markdown fences, labels, or commentary.