---
name: google-workspace
description: "Gmail, Calendar, Drive, Docs, Sheets via gws CLI or Python."
source: BUNDLED
requires_cli: gws,python3
platforms: jvm
---

# Google Workspace

Gmail, Calendar, Drive, Contacts, Sheets, and Docs — through OAuth and a thin CLI wrapper. When `gws` is installed, the skill uses it as the execution backend; otherwise it falls back to the bundled Python client.

## References

- `references/gmail-search-syntax.md` — Gmail search operators (is:unread, from:, newer_than:, etc.)

## First-Time Setup

The setup is fully non-interactive — you drive it step by step so it works on any platform.

### Step 0: Check if already set up

```
execute_command(command="python3", args=["scripts/setup.py", "--check"])
```

If it prints `AUTHENTICATED`, skip to Usage — setup is already done.

### Step 1: Triage — ask the user what they need

Before starting OAuth setup, ask the user TWO questions:

**Question 1: "What Google services do you need? Just email, or also Calendar/Drive/Sheets/Docs?"**

- **Email only** → They don't need this skill at all. Use the `himalaya` skill instead — it works with a Gmail App Password and takes 2 minutes. No Google Cloud project needed.
- **Email + Calendar** → Continue with `--services email,calendar`
- **Calendar/Drive/Sheets/Docs only** → Use `--services calendar,drive,sheets,docs`
- **Full Workspace access** → Use default `all` service set.

**Question 2: "Does your Google account use Advanced Protection (hardware security keys)?"**

- **No / Not sure** → Normal setup. Continue below.
- **Yes** → Their Workspace admin must add the OAuth client ID to the org's allowed apps list before Step 4 will work.

### Step 2: Create OAuth credentials (one-time, ~5 minutes)

Tell the user:

> You need a Google Cloud OAuth client. This is a one-time setup:
>
> 1. Create or select a project:
>    https://console.cloud.google.com/projectselector2/home/dashboard
> 2. Enable the required APIs from the API Library:
>    https://console.cloud.google.com/apis/library
>    Enable: Gmail API, Google Calendar API, Google Drive API,
>    Google Sheets API, Google Docs API, People API
> 3. Create the OAuth client here:
>    https://console.cloud.google.com/apis/credentials
>    Credentials → Create Credentials → OAuth 2.0 Client ID
> 4. Application type: "Desktop app" → Create
> 5. If the app is still in Testing, add the user's Google account as a test user:
>    https://console.cloud.google.com/auth/audience
> 6. Download the JSON file and provide the file path

Once they provide the path:

```
execute_command(command="python3", args=["scripts/setup.py", "--client-secret", "/path/to/client_secret.json"])
```

If they paste raw client ID / client secret values instead, use `file_write` to create a valid Desktop OAuth JSON file, then run `--client-secret` against it.

### Step 3: Get authorization URL

Use the service set chosen in Step 1:

```
execute_command(command="python3", args=["scripts/setup.py", "--auth-url", "--services", "email,calendar", "--format", "json"])
execute_command(command="python3", args=["scripts/setup.py", "--auth-url", "--services", "all", "--format", "json"])
```

Agent rules for this step:
- Extract the `auth_url` field and send that exact URL to the user.
- Tell the user the browser will likely fail on `http://localhost:1` after approval — this is expected.
- Tell them to copy the ENTIRE redirected URL from the browser address bar.
- If `Error 403: access_denied`, send them to `https://console.cloud.google.com/auth/audience` to add themselves as a test user.

### Step 4: Exchange the redirect URL for tokens

```
execute_command(command="python3", args=["scripts/setup.py", "--exchange", "REDIRECT_URL_HERE"])
```

### Step 5: Verify

```
execute_command(command="python3", args=["scripts/setup.py", "--check"])
```

## Usage

### Gmail

```
execute_command(command="python3", args=["scripts/google_api.py", "gmail", "list", "--query", "is:unread newer_than:1d"])
execute_command(command="python3", args=["scripts/google_api.py", "gmail", "read", "--id", "MSG_ID"])
execute_command(command="python3", args=["scripts/google_api.py", "gmail", "send", "--to", "user@example.com", "--subject", "Hello", "--body", "Message body"])
```

### Calendar

```
execute_command(command="python3", args=["scripts/google_api.py", "calendar", "list", "--days", "7"])
execute_command(command="python3", args=["scripts/google_api.py", "calendar", "create", "--title", "Meeting", "--start", "2026-01-15T10:00", "--end", "2026-01-15T11:00"])
```

### Drive

```
execute_command(command="python3", args=["scripts/google_api.py", "drive", "list", "--query", "name contains 'report'"])
execute_command(command="python3", args=["scripts/google_api.py", "drive", "download", "--id", "FILE_ID", "--output", "report.pdf"])
execute_command(command="python3", args=["scripts/google_api.py", "drive", "upload", "--file", "report.pdf", "--folder", "FOLDER_ID"])
```

### Sheets

```
execute_command(command="python3", args=["scripts/google_api.py", "sheets", "read", "--id", "SPREADSHEET_ID", "--range", "Sheet1!A1:D10"])
execute_command(command="python3", args=["scripts/google_api.py", "sheets", "write", "--id", "SPREADSHEET_ID", "--range", "Sheet1!A1", "--values", "[[\"a\",\"b\"],[\"c\",\"d\"]]"])
```

### Docs

```
execute_command(command="python3", args=["scripts/google_api.py", "docs", "read", "--id", "DOC_ID"])
execute_command(command="python3", args=["scripts/google_api.py", "docs", "create", "--title", "New Doc", "--body", "Content here"])
```

## With `gws` CLI (when available)

If `gws` is installed, the Python wrapper delegates to it automatically. You can also call `gws` directly:

```
execute_command(command="gws", args=["gmail", "list", "--unread", "--limit", "10"])
execute_command(command="gws", args=["calendar", "today"])
execute_command(command="gws", args=["drive", "search", "quarterly report"])
```
