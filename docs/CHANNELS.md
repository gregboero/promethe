# Messaging Channels Guide

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

> Detailed configuration for the 19 messaging channels supported by Prométhé.

## Overview

Prométhé can receive and send messages via 19 channels. Each channel requires:
1. An account/bot on the platform
2. Tokens/API keys configured in Prométhé
3. A webhook pointing to your gateway

**Webhook base URL**: `https://your-domain.com/webhook/{channel}`

---

## 1. Telegram

### Setup

1. **Create a bot**: Open [@BotFather](https://t.me/BotFather) on Telegram
2. Send `/newbot` and follow the instructions
3. Retrieve the **Bot Token** (format: `123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11`)
4. Generate a **Secret Token** (random string to verify webhooks)

### Environment variables

```bash
TELEGRAM_BOT_TOKEN=123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11
TELEGRAM_SECRET_TOKEN=mon_secret_aleatoire_32chars
```

### Configure the webhook

```bash
curl -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://promethe.example.com/webhook/telegram",
    "secret_token": "mon_secret_aleatoire_32chars"
  }'
```

### Test

```bash
# Send a message to the bot on Telegram, then check the logs:
curl http://localhost:8080/api/v1/channels
```

### Troubleshooting

| Problem | Solution |
|---|---|
| Webhook not received | Verify the URL is HTTPS (required by Telegram) |
| 401 Unauthorized | Check the `TELEGRAM_SECRET_TOKEN` |
| Bot not responding | Check `/setWebhook` with `getWebhookInfo` |

---

## 2. Discord

### Setup

1. Go to the [Discord Developer Portal](https://discord.com/developers/applications)
2. Create a **New Application**
3. **Bot** tab → Create a bot → Copy the **Bot Token**
4. To enable Message Content, turn on **Privileged Gateway Intents → Message Content Intent** in the **Bot** tab
5. **OAuth2** tab → URL Generator → Select the `bot` scope
6. Grant **View Channels**, **Send Messages**, and **Read Message History**
7. Invite the bot to your server with the generated URL
8. Save the token and Message Content setting in Promethe. The Gateway reconnects immediately.

### Environment variables

```bash
DISCORD_BOT_TOKEN=MTI...xyz
DISCORD_MESSAGE_CONTENT_ENABLED=false
DISCORD_ALLOWED_USER_IDS=123456789012345678,234567890123456789
DISCORD_APPROVER_USER_IDS=123456789012345678
DISCORD_KNOWLEDGE_CHANNEL_IDS=345678901234567890
```

`DISCORD_PUBLIC_KEY` is optional. It is used only for signed HTTP interactions such as slash
commands; direct messages and server mentions use the persistent Discord Gateway connection.

Enable Discord **Developer Mode**, then use **Copy User ID** and **Copy Channel ID** to obtain the
numeric values. Leave `DISCORD_ALLOWED_USER_IDS` empty to preserve the existing behavior. When it is
set, only listed users may trigger the agent through mentions, replies, direct messages, or slash
commands. Invalid entries are ignored; a non-empty list containing no valid ID denies everyone.

Set `DISCORD_APPROVER_USER_IDS` to the owner or integration managers who should receive private
authorization requests when a Discord conversation reaches an effectful tool. Approvers must share a
server with the bot and permit direct messages. Button actions are accepted only from IDs in this list;
local coding-agent actions remain restricted to the local Desktop owner. Exact `CONFIG_CHANGE` requests
also provide an Always allow action. That grant is stored in SQLite, survives restarts and can be revoked
by the local owner; no process, file or arbitrary tool execution can receive that scope.

`DISCORD_KNOWLEDGE_CHANNEL_IDS` is an explicit opt-in archive. Every new human message received in a
listed channel, including messages from users who cannot invoke the agent, and every Promethe reply is
written as one JSON-LD [`schema.org/Message`](https://schema.org/Message) document under
`~/.promethe/knowledge/discord/<guild-id>/<channel-id>/`. Promethe may use recent messages from that
same channel as untrusted conversation context when an authorized user invokes it. Existing Discord
history is not imported, attachments are recorded only as metadata and URLs, and edits, deletions,
reactions, and messages from other bots are not archived.

### Live owner policy

The authenticated owner can update Discord rules from a normal Promethe conversation. For example:

- `Autorise <@123456789012345678> à parler de météo et de transports.`
- `Refuse désormais <@123456789012345678>.`
- `Ajoute <#345678901234567890> à l'écoute et associe-le au projet project-release.`
- `Arrête d'archiver <#345678901234567890>.`

Promethe maps these requests to the `discord_policy` tool. Listing is read-only; every mutation is a
`CONFIG_CHANGE` requiring owner approval. The tool is rejected when invoked from Discord, webhooks,
ACP, MCP, voice, schedules or autonomous goals, so a Discord participant cannot change their own rule.
The approval card appears directly in the initiating local chat and offers Always allow for the exact
configuration fingerprint.

Runtime rules are persisted in SQLite and applied immediately to Gateway messages and signed Discord
interactions. An `ALLOW` rule with subjects accepts a message only when it contains one of those
accent-insensitive phrases. An empty subject list allows every subject. A `DENY` rule takes precedence.
Creating the first dynamic `ALLOW` rule turns the dynamic rules into an allow-list; with only `DENY`
rules, other users keep the existing fallback behavior. Static environment lists remain compatible:
dynamic rules can refine, deny or extend them.

A listened channel captures all new human messages as Open Knowledge. When it is associated with a
Promethe project, addressed messages and slash commands also use that project's workspace, instructions
and memory. Enabling the first listened channel may cause one short JDA reconnect to request
`GatewayIntent.MESSAGE_CONTENT`; later user, subject and project changes do not interrupt the connection.

### Configure interactions (optional)

For slash commands, copy the **Public Key** from **General Information**, add the
`applications.commands` OAuth2 scope, and configure:

```bash
DISCORD_PUBLIC_KEY=abc123def456...
```

Then set in the Developer Portal:

- **Interactions Endpoint URL**: `https://promethe.example.com/webhook/discord`

### Test

```bash
# Mention the bot in a Discord channel
@PrometheBot bonjour
```

### Troubleshooting

| Problem | Solution |
|---|---|
| Invalid interaction | Check the `DISCORD_PUBLIC_KEY` (Ed25519 signature) |
| Bot offline | Check `DISCORD_BOT_TOKEN`, save settings again, and look for `Discord Gateway ready` in the gateway log |
| Mention ignored | Ensure the bot can view the channel and that the mention resolves to the bot user |
| Reply rejected | Grant **Send Messages** and **Read Message History** in the channel |

Promethe always responds to direct messages and explicit mentions. Setting
`DISCORD_MESSAGE_CONTENT_ENABLED=true` also lets it recognize messages beginning with its Discord
username or server nickname (for example, `Promethe, help me`) and replies to one of its messages.
Name matching ignores accents and tolerates a small spelling error, so common variants such as
`Promete` and `Promethee` work too. The name must remain at the beginning of the message, optionally
after a greeting. Other server conversation is ignored unless its channel is explicitly listed in
`DISCORD_KNOWLEDGE_CHANNEL_IDS` or the live owner policy. Addressed-message mode or any knowledge channel makes JDA request
`GatewayIntent.MESSAGE_CONTENT`; Discord must also have the intent enabled in the Developer Portal or
the Gateway connection is rejected.

---

## 3. Slack

### Setup

1. Go to [api.slack.com/apps](https://api.slack.com/apps) → **Create New App**
2. Choose **From scratch** → Name the app → Select the workspace
3. **OAuth & Permissions** → Bot Token Scopes:
   - `chat:write`, `channels:read`, `im:read`, `im:write`, `app_mentions:read`
4. **Install to Workspace** → Copy the **Bot User OAuth Token** (`xoxb-...`)
5. **Basic Information** → Copy the **Signing Secret**
6. **Event Subscriptions** → Enable → Request URL: `https://promethe.example.com/webhook/slack`
7. Subscribe to bot events: `message.im`, `app_mention`

### Environment variables

```bash
SLACK_BOT_TOKEN=xoxb-1234567890-abcdefghijk
SLACK_SIGNING_SECRET=abc123def456789
```

### Test

```bash
# Mention the bot in a Slack channel
@Promethe bonjour

# Or send a DM to the bot
```

### Troubleshooting

| Problem | Solution |
|---|---|
| URL verification failed | The gateway must respond to the verification challenge |
| Invalid signature | Check the `SLACK_SIGNING_SECRET` (HMAC-SHA256) |
| Bot doesn't see messages | Check the Event Subscriptions and scopes |

---

## 4. WhatsApp

### Setup

1. Create a [Meta Business](https://business.facebook.com/) account
2. Go to **Meta for Developers** → Create a **Business** type app
3. Add the **WhatsApp** product
4. **API Setup** → Copy the **Phone Number ID** and the **Access Token**
5. **Webhooks** → Configure → Callback URL: `https://promethe.example.com/webhook/whatsapp`
6. Subscribe to the fields: `messages`

### Environment variables

```bash
WHATSAPP_PHONE_NUMBER_ID=123456789012345
WHATSAPP_ACCESS_TOKEN=EAAGx...
```

### Test

```bash
# Send a message to the configured WhatsApp Business number
# Check in the logs that the webhook is received

curl -X POST "https://graph.facebook.com/v18.0/${WHATSAPP_PHONE_NUMBER_ID}/messages" \
  -H "Authorization: Bearer ${WHATSAPP_ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"messaging_product":"whatsapp","to":"33612345678","type":"text","text":{"body":"Test"}}'
```

### Troubleshooting

| Problem | Solution |
|---|---|
| Webhook not verified | Meta sends a GET challenge — the gateway must return it |
| Token expired | Generate a permanent token in the app settings |
| Message not delivered | Check that the recipient number has initiated a conversation |

---

## 5. Signal

### Setup

1. Install [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api):
   ```bash
   docker run -d --name signal-api \
     -p 8100:8080 \
     -v signal-data:/home/.local/share/signal-cli \
     bbernhard/signal-cli-rest-api
   ```
2. Register a phone number:
   ```bash
   curl -X POST "http://localhost:8100/v1/register/+33612345678"
   # Receive the SMS code and verify:
   curl -X POST "http://localhost:8100/v1/register/+33612345678/verify/123456"
   ```

### Environment variables

```bash
SIGNAL_CLI_REST_URL=http://localhost:8100
SIGNAL_PHONE_NUMBER=+33612345678
```

### Test

```bash
# Send a test message via signal-cli
curl -X POST "http://localhost:8100/v2/send" \
  -H "Content-Type: application/json" \
  -d '{"message":"Test","number":"+33612345678","recipients":["+33698765432"]}'
```

### Troubleshooting

| Problem | Solution |
|---|---|
| signal-cli doesn't start | Check the Docker volumes and logs |
| Number not registered | Redo the register + verify procedure |
| Rate limited | Signal limits sending — space out messages |

---

## 6. Matrix

### Setup

1. Create a bot account on your Matrix server (or matrix.org):
   ```bash
   curl -X POST "https://matrix.org/_matrix/client/v3/register" \
     -H "Content-Type: application/json" \
     -d '{"username":"promethe-bot","password":"motdepasse","auth":{"type":"m.login.dummy"}}'
   ```
2. Log in to obtain an **Access Token**:
   ```bash
   curl -X POST "https://matrix.org/_matrix/client/v3/login" \
     -H "Content-Type: application/json" \
     -d '{"type":"m.login.password","user":"promethe-bot","password":"motdepasse"}'
   # → {"access_token":"syt_...","device_id":"ABCDEF"}
   ```

### Environment variables

```bash
MATRIX_HOMESERVER_URL=https://matrix.org
MATRIX_ACCESS_TOKEN=syt_cHJvbWV0aGUtYm90_...
```

### Join a room

```bash
curl -X POST "https://matrix.org/_matrix/client/v3/join/%23mon-salon:matrix.org" \
  -H "Authorization: Bearer ${MATRIX_ACCESS_TOKEN}"
```

### Test

```bash
# Send a message in a room
curl -X PUT "https://matrix.org/_matrix/client/v3/rooms/!abc:matrix.org/send/m.room.message/txn1" \
  -H "Authorization: Bearer ${MATRIX_ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"msgtype":"m.text","body":"Test depuis Prométhé"}'
```

### Troubleshooting

| Problem | Solution |
|---|---|
| 403 Forbidden | The bot is not a member of the room — invite it first |
| Token expired | Matrix tokens don't expire by default (unless server config) |
| Slow sync | Use the `/sync?filter=...` filter to limit data |

---

## 7. Email (SMTP / SendGrid / Mailgun)

### Setup

Two options:
- **Option A** — Incoming webhooks (SendGrid Inbound Parse / Mailgun Routes) + HTTP API for sending
- **Option B** — IMAP polling (requires `jakarta.mail:2.0.1` on the classpath)

### Environment variables

```bash
EMAIL_SMTP_HOST=smtp.sendgrid.net          # or smtp.mailgun.org
EMAIL_SMTP_PORT=587                         # 587 (TLS) or 465 (SSL)
EMAIL_USERNAME=apikey                       # Or your SMTP username
EMAIL_PASSWORD=SG.xxxxx                     # SendGrid API key or password
EMAIL_FROM_ADDRESS=promethe@example.com
EMAIL_API_MODE=SENDGRID                     # SENDGRID, MAILGUN, or SMTP_RAW
```

### API modes

| Mode | Sending | Receiving |
|---|---|---|
| `SENDGRID` | SendGrid API v3 | Inbound Parse webhook |
| `MAILGUN` | Mailgun API | Routes webhook |
| `SMTP_RAW` | Direct SMTP socket | IMAP polling (additional dependency) |

---

## 8. SMS (Twilio)

### Setup

1. Create an account on [twilio.com](https://www.twilio.com)
2. Retrieve the **Account SID** and **Auth Token** from the dashboard
3. Buy or configure a phone number
4. Configure the webhook URL for incoming messages

### Environment variables

```bash
TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_AUTH_TOKEN=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_PHONE_NUMBER=+14155552671             # E.164 format
PUBLIC_BASE_URL=https://promethe.example.com
```

### Configure the webhook

In the Twilio console → Phone Numbers → your number → Messaging:
- **When a message comes in**: `https://promethe.example.com/webhook/sms`
- **HTTP Method**: POST

Promethe validates `X-Twilio-Signature`, acknowledges the webhook immediately with empty TwiML,
then sends the message through the same A2A agent loop as the other channels. The agent reply is
sent asynchronously through Twilio. Settings changes apply without restarting the gateway.

### Troubleshooting

| Problem | Solution |
|---|---|
| 401 Unauthorized | Check Account SID + Auth Token |
| Invalid Twilio signature | Ensure `PUBLIC_BASE_URL` exactly matches the public HTTPS URL configured in Twilio |
| Number not verified | In trial mode, the recipient must be verified |
| Truncated replies | Promethe limits an agent reply to three messages of 1500 characters to bound cost |

---

## 9. Microsoft Teams

### Setup

1. Register a bot in [Azure Bot Service](https://portal.azure.com/#create/Microsoft.AzureBot)
2. Retrieve the **App ID** and **App Secret**
3. Configure the **Messaging endpoint**: `https://promethe.example.com/webhook/teams`

### Environment variables

```bash
TEAMS_APP_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
TEAMS_APP_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### Authentication

Teams uses OAuth2 with `login.microsoftonline.com`. The channel automatically obtains a Bearer token to respond to activities (messages, typing indicators, proactive messages).

### Troubleshooting

| Problem | Solution |
|---|---|
| 401 on responses | Check App ID + Secret, regenerate if necessary |
| Bot not found | Check that the bot is published in Teams Admin Center |
| No messages received | Check the messaging endpoint in Azure Bot Service |

---

## 10. Mattermost

### Setup

1. Create a **Bot Account** or a **Personal Access Token** in Administration → Integrations
2. Configure an **Outgoing Webhook** pointing to `https://promethe.example.com/webhook/mattermost`
3. Grant the bot permission to post in the target channels

### Environment variables

```bash
MATTERMOST_URL=https://mattermost.example.com
MATTERMOST_ACCESS_TOKEN=xxxxxxxxxxxxxxxxxxxxxxxx
```

### API

Uses the Mattermost REST API v4 (`/api/v4/posts`). Supports:
- Simple messages and threads (via `root_id`)
- Rich attachments (Markdown formatting)
- Typing indicators

### Troubleshooting

| Problem | Solution |
|---|---|
| 403 Forbidden | Check the bot's permissions in the channel |
| Webhook not received | Check the outgoing webhook and the trigger word |
| Token expired | PATs don't expire; session tokens do |

---

## 11. DingTalk (钉钉)

### Setup

1. Create a **Custom Robot** in a DingTalk group
2. Choose the security mode: **Keywords** or **Sign** (HMAC-SHA256)
3. To receive messages: configure an **Event Subscription** callback URL

### Environment variables

```bash
DINGTALK_WEBHOOK_URL=https://oapi.dingtalk.com/robot/send?access_token=xxxxx
DINGTALK_SECRET=SECxxxxxxxxx                # HMAC secret (empty in keywords mode)
```

### Security

In **Sign** mode, each request is signed with HMAC-SHA256:
- Timestamp + secret → Base64 signature
- Added as query param `&sign=xxx&timestamp=xxx`

### Supported message types

| Type | Description |
|---|---|
| `text` | Simple text message |
| `markdown` | Markdown-formatted message |
| `actionCard` | Interactive card with buttons |

---

## 12. Feishu / Lark (飞书)

### Setup

1. Create an app on [open.feishu.cn](https://open.feishu.cn) (or [open.larksuite.com](https://open.larksuite.com) for international Lark)
2. Retrieve the **App ID** and **App Secret**
3. Enable the **Bot** capability and subscribe to the `im.message.receive_v1` event
4. Configure the **Event Subscription URL**: `https://promethe.example.com/webhook/feishu`

### Environment variables

```bash
FEISHU_APP_ID=cli_xxxxxxxxxxxxxxxx
FEISHU_APP_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
FEISHU_IS_LARK=false                         # true for international Lark
```

### Authentication

Feishu uses a **Tenant Access Token** (automatically refreshed, expires every 2h). The channel handles renewal automatically.

### Verification challenge

When configuring the Event Subscription URL, Feishu sends a challenge which the channel handles automatically (`{ "challenge": "xxx" }` → returns `{ "challenge": "xxx" }`).

---

## 13. WeCom / WeChat Work (企业微信)

### Two modes

| Mode | Usage | Auth |
|---|---|---|
| **Group Robot Webhook** | Sending only, within a group | Webhook key in the URL |
| **Server API** | Bidirectional, all users | Corp ID + Secret → Access Token |

### Environment variables

```bash
# Robot Webhook mode (simple)
WECOM_WEBHOOK_KEY=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx

# Server API mode (full)
WECOM_CORP_ID=wxxxxxxxxxxxxxxxxx
WECOM_CORP_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
WECOM_AGENT_ID=1000002
```

### Supported message types

| Type | Webhook | Server API |
|---|---|---|
| `text` | ✅ | ✅ |
| `markdown` | ✅ | ✅ |
| `textcard` | ❌ | ✅ |

---

## 14. LINE

### Setup

1. Create a **Messaging API channel** on the [LINE Developers Console](https://developers.line.biz)
2. Retrieve the **Channel Access Token**
3. Configure the **Webhook URL**: `https://promethe.example.com/webhook/line`

### Environment variables

```bash
LINE_CHANNEL_ACCESS_TOKEN=xxxxxxxxxxxxxxxxxxxxxxxx
```

### Features

| Feature | Supported |
|---|---|
| Reply (via replyToken) | ✅ |
| Push message (proactive) | ✅ |
| Typing indicator | ❌ (not supported by the LINE API) |

---

## 15. QQ Bot (QQ 频道)

### Setup

1. Create a bot on the [QQ Bot Platform](https://bot.q.qq.com)
2. Retrieve the **App ID** and **Token**
3. Configure the webhook for message events

### Environment variables

```bash
QQ_APP_ID=xxxxxxxxxx
QQ_TOKEN=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### API

Uses the official QQ Bot API v2 (`https://api.sgroup.qq.com`). Auth via header `Bot {appId}.{token}`.

---

## 16. Weixin / WeChat Official Account (公众号)

### Setup

1. Create an Official Account on [mp.weixin.qq.com](https://mp.weixin.qq.com)
2. Retrieve the **App ID** and **App Secret**
3. Configure the **Server URL** in the developer settings

### Environment variables

```bash
WEIXIN_APP_ID=wxxxxxxxxxxxxxxxxxxx
WEIXIN_APP_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### Particularities

- Incoming messages in **XML** (not JSON)
- Synchronous replies in XML or asynchronous via the Customer Service Message API
- Auto-refreshed Access Token (expires every 2h)
- ⚠️ This is for **official accounts**, not personal WeChat

---

## 17. BlueBubbles (iMessage)

### Setup

1. Install [BlueBubbles Server](https://bluebubbles.app/) on a Mac
2. Configure the server with a password
3. Configure the webhook for incoming messages: `https://promethe.example.com/webhook/bluebubbles`

### Environment variables

```bash
BLUEBUBBLES_SERVER_URL=http://192.168.1.100:1234
BLUEBUBBLES_PASSWORD=xxxxxxxxxx
```

### Particularities

- Requires a **Mac** with iMessage permanently connected
- Uses AppleScript to send messages
- No typing indicator (unreliable)

---

## 18. ntfy

### Setup

Push notifications via [ntfy.sh](https://ntfy.sh/) (self-hosted or cloud). Ideal for alerts and simple notifications.

1. Choose a unique topic (e.g.: `promethe-alerts`)
2. Subscribe to the topic on your device (mobile, desktop)
3. Configure the webhook to receive messages: `https://promethe.example.com/webhook/ntfy`

### Environment variables

```bash
NTFY_SERVER_URL=https://ntfy.sh              # or your self-hosted instance
NTFY_DEFAULT_TOPIC=promethe-alerts
```

### Features

| Feature | Supported |
|---|---|
| Sending notifications | ✅ |
| Custom title | ✅ |
| Multiple topics | ✅ |
| Typing indicator | ❌ (push-only) |

---

## 19. Home Assistant

### Setup

1. Obtain a **Long-Lived Access Token** in Home Assistant → Profile → Security
2. Configure the webhook for text/voice commands

### Environment variables

```bash
HA_URL=http://homeassistant.local:8123
HA_TOKEN=eyJhbGci...                         # Long-Lived Access Token
```

### API

Uses the Home Assistant Conversation API (`/api/conversation/process`). Supports:
- Text and voice commands
- Conversation tracking via `conversation_id`
- Multilingual (`language` parameter)

---

## Configuration via the UI

All channels can be configured via:
- **Setup Screen** (step 3) — on first launch
- **Settings > Integrations** — to modify tokens
- **Channels Screen** — to view status and test each channel

## Channel management API

```bash
# List all channels and their status
curl http://localhost:8080/api/v1/channels

# Configure a channel
curl -X PUT http://localhost:8080/api/v1/channels/telegram \
  -H "Content-Type: application/json" \
  -d '{"enabled": true}'

# Test a channel
curl -X POST http://localhost:8080/api/v1/channels/telegram/test
```
