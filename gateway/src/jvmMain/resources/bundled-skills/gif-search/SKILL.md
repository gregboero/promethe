---
name: gif-search
description: "Search/download GIFs from Tenor via HTTP API."
source: BUNDLED
platforms: jvm
---

# GIF Search (Tenor API)

Search and download GIFs directly via the Tenor API. No extra tools needed.

## When to Use

Useful for finding reaction GIFs, creating visual content, and sending GIFs in chat.

## Setup

Set your Tenor API key as an environment variable:

```
TENOR_API_KEY=your_key_here
```

Get a free API key at https://developers.google.com/tenor/guides/quickstart — the Google Cloud Console Tenor API key is free and has generous rate limits.

## Prerequisites

- `TENOR_API_KEY` environment variable

## Search for GIFs

### Via http_fetch

```
# Search and get GIF URLs
http_fetch(url="https://tenor.googleapis.com/v2/search?q=thumbs+up&limit=5&key=${TENOR_API_KEY}", method="GET")

# Get smaller/preview versions — use tinygif format
http_fetch(url="https://tenor.googleapis.com/v2/search?q=nice+work&limit=3&key=${TENOR_API_KEY}", method="GET")
```

### Via execute_command (curl + jq)

```
execute_command(command="curl", args=["-s", "https://tenor.googleapis.com/v2/search?q=thumbs+up&limit=5&key=${TENOR_API_KEY}"])
```

## Response Format

The API returns a JSON object with a `results` array. Each result contains:
- `media_formats.gif.url` — full-size GIF
- `media_formats.tinygif.url` — small preview GIF
- `media_formats.mp4.url` — MP4 version (smaller file size)
- `content_description` — text description of the GIF

## Download a GIF

```
execute_command(command="curl", args=["-sL", "<gif_url>", "-o", "reaction.gif"])
```

## Trending GIFs

```
http_fetch(url="https://tenor.googleapis.com/v2/featured?limit=10&key=${TENOR_API_KEY}", method="GET")
```
