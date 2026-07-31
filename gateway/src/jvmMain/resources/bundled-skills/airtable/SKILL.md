---
name: airtable
description: "Airtable REST API — Records CRUD, filters, upserts."
source: BUNDLED
requires_cli: curl
platforms: jvm
---

# Airtable — Bases, Tables & Records

Work with Airtable's REST API using `http_fetch` or `execute_command` with `curl`. No SDK required — just a personal access token.

## Prerequisites

1. Create a **Personal Access Token (PAT)** at https://airtable.com/create/tokens (tokens start with `pat...`).
2. Grant these scopes (minimum):
   - `data.records:read` — read rows
   - `data.records:write` — create / update / delete rows
   - `schema.bases:read` — list bases and tables
3. **Important:** in the token UI, add each base you want to access to the token's **Access** list. PATs are scoped per-base — a valid token on the wrong base returns `403`.
4. Store the token as an environment variable:
   ```
   AIRTABLE_API_KEY=pat_your_token_here
   ```

> Note: legacy `key...` API keys were deprecated Feb 2024. Only PATs and OAuth tokens work now.

## API Basics

- **Endpoint:** `https://api.airtable.com/v0`
- **Auth header:** `Authorization: Bearer $AIRTABLE_API_KEY`
- **All requests** use JSON (`Content-Type: application/json` for any POST/PATCH/PUT body).
- **Object IDs:** bases `app...`, tables `tbl...`, records `rec...`, fields `fld...`. IDs never change; names can. Prefer IDs in automations.
- **Rate limit:** 5 requests/sec/base. `429` → back off.

### Fetching via http_fetch

```
http_fetch(url="https://api.airtable.com/v0/$BASE_ID/$TABLE?maxRecords=5", method="GET", headers={"Authorization": "Bearer $AIRTABLE_API_KEY"})
```

### Fetching via execute_command

```bash
execute_command(command="curl", args=["-s", "https://api.airtable.com/v0/$BASE_ID/$TABLE?maxRecords=5", "-H", "Authorization: Bearer $AIRTABLE_API_KEY"])
```

## Field Types (request body shapes)

| Field type | Write shape |
|---|---|
| Single line text | `"Name": "hello"` |
| Long text | `"Notes": "multi\nline"` |
| Number | `"Score": 42` |
| Checkbox | `"Done": true` |
| Single select | `"Status": "Todo"` (name must exist unless `typecast: true`) |
| Multi-select | `"Tags": ["urgent", "bug"]` |
| Date | `"Due": "2026-04-01"` |
| DateTime (UTC) | `"At": "2026-04-01T14:30:00.000Z"` |
| URL / Email / Phone | `"Link": "https://…"` |
| Attachment | `"Files": [{"url": "https://…"}]` (Airtable fetches + rehosts) |
| Linked record | `"Owner": ["recXXXXXXXXXXXXXX"]` (array of record IDs) |
| User | `"AssignedTo": {"id": "usrXXXXXXXXXXXXXX"}` |

Pass `"typecast": true` at the top level of a create/update body to let Airtable auto-coerce values (e.g. create a new select option on the fly).

## Common Queries

### List bases the token can see
```
http_fetch(url="https://api.airtable.com/v0/meta/bases", method="GET", headers={"Authorization": "Bearer $AIRTABLE_API_KEY"})
```

### List tables + schema for a base
```
http_fetch(url="https://api.airtable.com/v0/meta/bases/$BASE_ID/tables", method="GET", headers={"Authorization": "Bearer $AIRTABLE_API_KEY"})
```
Use this BEFORE mutating — confirms exact field names and IDs, surfaces `options.choices` for select fields, and shows primary-field names.

### List records (first 10)
```
http_fetch(url="https://api.airtable.com/v0/$BASE_ID/$TABLE?maxRecords=10", method="GET", headers={"Authorization": "Bearer $AIRTABLE_API_KEY"})
```

### Get a single record
```
http_fetch(url="https://api.airtable.com/v0/$BASE_ID/$TABLE/$RECORD_ID", method="GET", headers={"Authorization": "Bearer $AIRTABLE_API_KEY"})
```

### Filter records (filterByFormula)
Airtable formulas must be URL-encoded. Example:
```
http_fetch(url="https://api.airtable.com/v0/$BASE_ID/$TABLE?filterByFormula=%7BStatus%7D%3D%27Todo%27&maxRecords=20", method="GET", headers={"Authorization": "Bearer $AIRTABLE_API_KEY"})
```

Useful formula patterns:
- Exact match: `{Email}='user@example.com'`
- Contains: `FIND('bug', LOWER({Title}))`
- Multiple conditions: `AND({Status}='Todo', {Priority}='High')`
