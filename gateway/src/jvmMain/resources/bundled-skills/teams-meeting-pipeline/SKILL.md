---
name: teams-meeting-pipeline
description: "Operate the Teams meeting summary pipeline — summarize meetings, inspect pipeline status, replay jobs, manage Microsoft Graph subscriptions."
source: BUNDLED
platforms: jvm
---

# Teams Meeting Pipeline

Use this skill whenever the user asks about Microsoft Teams meeting summaries, transcripts, recordings, action items, Graph subscriptions, or any operational question about the Teams meeting pipeline.

Everything operator-facing is a pipeline subcommand run via `execute_command`. The CLI is the primary surface.

## When to Use

The user is asking to:
- Summarize a Teams meeting / extract action items / pull meeting notes
- Check pipeline status, inspect a stored meeting job, or see recent meetings
- Replay / re-run a stored job that failed or needs a fresh summary
- Validate Microsoft Graph setup after changing env or config
- Troubleshoot "meeting summary never arrived" or "no new meetings are ingesting"
- Manage Graph webhook subscriptions (create, renew, delete, inspect)
- Set up automated subscription renewal

## Prerequisites

Before using the pipeline, verify these environment variables are set:

```
MSGRAPH_TENANT_ID=...
MSGRAPH_CLIENT_ID=...
MSGRAPH_CLIENT_SECRET=...
```

If any are missing, the user needs an Azure AD app registration with admin-consented Graph application permissions before the pipeline will work.

## Command Reference

### Status and Inspection (start here)

```
execute_command(command="teams-pipeline", args=["validate"])
# config snapshot — run first after any change

execute_command(command="teams-pipeline", args=["token-health"])
# Graph token status

execute_command(command="teams-pipeline", args=["token-health", "--force-refresh"])
# force a fresh token acquisition

execute_command(command="teams-pipeline", args=["list"])
# recent meeting jobs

execute_command(command="teams-pipeline", args=["list", "--status", "failed"])
# only failed jobs

execute_command(command="teams-pipeline", args=["show", "<job-id>"])
# full detail of one job
```

### Summarize a Meeting

```
execute_command(command="teams-pipeline", args=["summarize", "<meeting-id>"])
```

### Replay a Failed Job

```
execute_command(command="teams-pipeline", args=["replay", "<job-id>"])
```

### Subscription Management

```
execute_command(command="teams-pipeline", args=["subscriptions", "list"])
execute_command(command="teams-pipeline", args=["subscriptions", "create"])
execute_command(command="teams-pipeline", args=["subscriptions", "renew", "<sub-id>"])
execute_command(command="teams-pipeline", args=["subscriptions", "delete", "<sub-id>"])
```

## Troubleshooting

1. **No meetings ingesting** → Run `validate` then `token-health`. Check that Graph permissions include `OnlineMeetings.Read.All` and `CallRecords.Read.All`.
2. **Subscription expired** → Graph subscriptions expire after 3 days max. Use `subscriptions renew` or set up a cron via `delegate_task` to auto-renew.
3. **Summary quality issues** → Run `show <job-id>` to inspect the transcript. Poor audio quality or speaker identification affects downstream summarization.
