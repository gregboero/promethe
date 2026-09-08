---
name: retrieve_ordered_page_answers_via_json_query
source: custom
lifecycle: DRAFT
version: 1
content_hash: 2b7ab35cd3c9c7f9060135b5486de43c2699e2018ee7dfe0bbc833bd65ef8f8d
provenance: trajectory_synthesis
---

# Skill: Retrieve Ordered Page Answers via JSON Query

## Objective
Read a specified consecutive range of pages exactly once each and return their `answer` values in page order as a JSON array.

## Prerequisites
- Access to a `json_query` tool that accepts a page identifier, such as `{"page": 0}`.
- The queried page responses must include an `answer` field.
- A known inclusive page range, for example pages 0 through 7.

## Steps
1. Identify the requested page range and enumerate every page in ascending order.
2. Call `json_query` once for the first page using `{"page": <page_number>}`.
3. From the response, extract only the value of the `answer` field and ignore fields such as `noise`.
4. Repeat the query-and-extract process exactly once for every remaining page in ascending order.
5. Preserve the extracted answers in the same order as their page numbers.
6. Return only a valid JSON array containing the ordered answer values, with no explanation or formatting wrappers.

## Tools Used
- `json_query`

## Notes
- Do not re-query pages that have already returned a usable `answer`; repeated calls increase cost and delay.
- Ignore irrelevant diagnostic or noise fields in tool responses.
- Ensure every requested page is queried, including both endpoints of the range.
- Validate that the final output is a JSON array and that its length matches the number of requested pages.