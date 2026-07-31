---
name: llm-wiki
description: "Karpathy's LLM Wiki: build and query interlinked markdown knowledge bases."
source: BUNDLED
platforms: jvm
---

# Karpathy's LLM Wiki — Promethe Guide

Build and maintain a persistent, compounding knowledge base as interlinked markdown files.
Based on [Andrej Karpathy's LLM Wiki pattern](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f).

Unlike traditional RAG (which rediscovers knowledge from scratch per query), the wiki
compiles knowledge once and keeps it current. Cross-references are already there.

## When to Use

- User asks to create, build, or start a wiki or knowledge base
- User asks to ingest, add, or process a source into their wiki
- User asks a question and an existing wiki is present
- User asks to lint, audit, or health-check their wiki

## Wiki Location

Default: `~/wiki` or configured via environment variable `WIKI_PATH`.

## Directory Layout

```
wiki/
├── README.md           # Welcome + table of contents
├── _index.md           # Machine-readable topic index (YAML frontmatter)
├── topic-name.md       # One file per topic
├── sources/            # Ingested raw sources
│   ├── source-001.md   # Summarized source with metadata
│   └── ...
└── _templates/
    ├── topic.md        # Template for new topics
    └── source.md       # Template for new sources
```

## Topic File Format

```markdown
---
title: Topic Name
created: 2026-06-26
updated: 2026-06-26
tags: [tag1, tag2]
sources: [source-001, source-003]
---

# Topic Name

## Summary
One-paragraph overview.

## Key Points
- Point 1
- Point 2

## Details
Extended content...

## See Also
- [[related-topic-1]]
- [[related-topic-2]]
```

## Workflow: Ingest a Source

1. User provides a URL, file, or paste
2. Read/fetch the content using `http_fetch` or `file_read`
3. Create a source file in `sources/source-NNN.md` with metadata
4. Extract key topics from the source
5. For each topic:
   - If file exists: update with new information, add source reference
   - If new: create from template, populate, add cross-references
6. Update `_index.md` with new/updated topics
7. Report: topics created, updated, cross-references added

## Workflow: Query the Wiki

1. Scan `_index.md` for relevant topics
2. Read the relevant topic files using `file_read`
3. Follow cross-references (`[[links]]`) to gather related context
4. Synthesize an answer from the wiki content
5. Cite the wiki files used

## Maintenance Commands

### Lint / Health Check
- Find orphan topics (no incoming links)
- Find broken `[[links]]`
- Find stale topics (not updated in 90+ days)
- Find duplicate/overlapping topics

### Rebuild Index
Regenerate `_index.md` from all topic files' frontmatter.

## Integration with Promethe Tools

- `file_read` / `file_write` — read and write wiki files
- `http_fetch` — fetch web sources for ingestion
- `code_grep` — search across wiki content
- `directory_tree` — list wiki structure
- `memory_save` — cache frequently accessed wiki paths
