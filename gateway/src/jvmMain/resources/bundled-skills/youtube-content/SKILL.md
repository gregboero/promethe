---
name: youtube-content
description: "YouTube transcripts to summaries, threads, blogs."
source: BUNDLED
requires_cli: python3
platforms: jvm
---

# YouTube Content Tool

## When to use

Use when the user shares a YouTube URL or video link, asks to summarize a video, requests a transcript, or wants to extract and reformat content from any YouTube video. Transforms transcripts into structured content (chapters, summaries, threads, blog posts).

## Setup

Install the transcript library:

```bash
execute_command(command="pip", args=["install", "youtube-transcript-api"])
```

## Fetching Transcripts

Use `execute_command` to run the transcript fetcher. The script accepts any standard YouTube URL format, short links (youtu.be), shorts, embeds, live links, or a raw 11-character video ID.

```bash
# JSON output with metadata
execute_command(command="python3", args=["SKILL_DIR/scripts/fetch_transcript.py", "https://youtube.com/watch?v=VIDEO_ID"])

# Plain text (good for piping into further processing)
execute_command(command="python3", args=["SKILL_DIR/scripts/fetch_transcript.py", "URL", "--text-only"])

# With timestamps
execute_command(command="python3", args=["SKILL_DIR/scripts/fetch_transcript.py", "URL", "--timestamps"])

# Specific language with fallback chain
execute_command(command="python3", args=["SKILL_DIR/scripts/fetch_transcript.py", "URL", "--language", "tr,en"])
```

## Output Formats

After fetching the transcript, format it based on what the user asks for:

- **Chapters**: Group by topic shifts, output timestamped chapter list
- **Summary**: Concise 5-10 sentence overview of the entire video
- **Thread**: Break into numbered posts (280-char limit per post) for social media
- **Blog Post**: Full prose article with headers, intro, and conclusion
- **Key Points**: Bullet-point list of main takeaways
- **Q&A**: Extract questions and answers discussed in the video

## Rules

1. Always fetch the transcript first before formatting.
2. Preserve key quotes and technical terms from the original.
3. Write output using `file_write(path=..., content=...)` for long-form content.
4. If transcript fetch fails, suggest the user check video availability or try a different language.
