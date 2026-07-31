---
name: spike
description: "Throwaway experiments to validate an idea before build."
source: BUNDLED
platforms: jvm
---

# Spike

Use this skill when the user wants to **feel out an idea** before committing to a real build — validating feasibility, comparing approaches, or surfacing unknowns that no amount of research will answer. Spikes are disposable by design. Throw them away once they've paid their debt.

Load this when the user says things like "let me try this", "I want to see if X works", "spike this out", "before I commit to Y", "quick prototype of Z", "is this even possible?", or "compare A vs B".

## When NOT to use this

- The answer is knowable from docs or reading code — just do research, don't build
- The work is production path — use the `plan` skill instead
- The idea is already validated — jump straight to implementation

## Core method

Regardless of scale, every spike follows this loop:

```
decompose  →  research  →  build  →  verdict
   ↑__________________________________________↓
                  iterate on findings
```

### 1. Decompose

Break the user's idea into **2-5 independent feasibility questions**. Each question is one spike. Present them as a table with Given/When/Then framing:

| # | Spike | Validates (Given/When/Then) | Risk |
|---|-------|----------------------------|------|
| 001 | websocket-streaming | Given a WS connection, when LLM streams tokens, then client receives chunks < 100ms | High |
| 002a | pdf-parse-pdfjs | Given a multi-page PDF, when parsed with pdfjs, then structured text is extractable | Medium |
| 002b | pdf-parse-camelot | Given a multi-page PDF, when parsed with camelot, then structured text is extractable | Medium |

**Spike types:**
- **standard** — one approach answering one question
- **comparison** — same question, different approaches (shared number, letter suffix `a`/`b`/`c`)

**Good spike questions:** specific feasibility with observable output.
**Bad spike questions:** too broad, no observable output, or just "read the docs about X".

**Order by risk.** The spike most likely to kill the idea runs first. No point prototyping the easy parts if the hard part doesn't work.

**Skip decomposition** only if the user already knows exactly what they want to spike and says so. Then take their idea as a single spike.

### 2. Align (for multi-spike ideas)

Present the spike table. Ask: "Build all in this order, or adjust?" Let the user drop, reorder, or re-frame before you write any code.

### 3. Research (per spike, before building)

Spikes are not research-free — you research enough to pick the right approach, then you build. Per spike:

1. **Brief it.** 2-3 sentences: what this spike is, why it matters, key risk.
2. **Surface competing approaches** if there's real choice:

   | Approach | Tool/Library | Pros | Cons | Status |
   |----------|-------------|------|------|--------|
   | ... | ... | ... | ... | maintained / abandoned / beta |

3. **Pick one.** State why. If 2+ are credible, build quick variants within the spike.
4. **Skip research** for pure logic with no external dependencies.

Use available tools for the research step:

- `http_fetch` to read library docs or API references
- `file_read` to inspect existing code for patterns
- `file_search` to find related implementations in the codebase
- `execute_command` to check installed versions, run quick tests

### 4. Build (per spike)

Build the **minimum throwaway code** that answers the feasibility question. Rules:

- **Isolate it.** Create a scratch directory (e.g., `spike-001-websocket/`). Don't pollute the main codebase.
- **Speed over quality.** No tests, no error handling, no abstractions. Copy-paste is fine.
- **Observable output.** The spike must produce something you can see: a log line, a screenshot, a diff, a benchmark number.
- **Time-box.** If a spike takes more than 30 minutes of build time, stop. The spike has answered its question: "this is harder than expected."

### 5. Verdict (per spike)

After building, record a clear verdict:

```markdown
## Spike 001: websocket-streaming

**Status:** ✅ VALIDATED / ❌ KILLED / ⚠️ PARTIAL

**Given:** A WS connection to the server
**When:** LLM streams 500 tokens
**Then:** Client receives chunks in < 100ms

**Observed:** Median chunk latency 47ms, p99 82ms. Works.

**Surprises:** Library X requires manual ping/pong handling.

**Recommendation:** Proceed with this approach. Note the ping/pong caveat in the implementation plan.
```

### 6. Synthesize (after all spikes)

Summarize all verdicts in one table. Recommend next steps:

| # | Spike | Verdict | Next Step |
|---|-------|---------|-----------|
| 001 | websocket-streaming | ✅ VALIDATED | Include in plan |
| 002a | pdf-parse-pdfjs | ❌ KILLED | Don't use |
| 002b | pdf-parse-camelot | ✅ VALIDATED | Include in plan |

### 7. Clean up

Spikes are throwaway. After the user has seen the verdicts:
- Delete spike directories (or let the user keep them as reference)
- Do NOT merge spike code into the main codebase
- If the spike validated an approach, create a proper implementation plan using the `plan` skill
