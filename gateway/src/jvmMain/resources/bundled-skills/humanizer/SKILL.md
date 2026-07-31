---
name: humanizer
description: "Humanize text: strip AI-isms and add real voice."
source: BUNDLED
platforms: jvm
---

# Humanizer: Remove AI Writing Patterns

Identify and remove signs of AI-generated text to make writing sound natural and human. Based on Wikipedia's "Signs of AI writing" guide, derived from observations of thousands of AI-generated text instances.

## When to use this skill

- User asks to "humanize", "de-AI", "de-slop", or "un-ChatGPT" text
- Rewrite something so it doesn't sound like it was written by an LLM
- Edit a draft (blog post, essay, PR description, docs, memo, email) to sound more natural
- Match the user's voice in writing they're producing
- Review text for AI tells before publishing

Also apply to **your own** output when writing user-facing prose.

## How to use it

The text usually arrives one of three ways:
1. **Inline** — user pastes text directly. Work on it in-place, reply with the rewrite.
2. **File** — user points at a file. Use `file_read(path=...)` to load it, then `file_write(path=..., content=...)` to apply edits.
3. **Voice calibration sample** — user provides a sample of their own writing. Read the sample first, then rewrite to match.

Always show the rewrite to the user. For file edits, show a diff or the changed section.

## Your task

1. **Identify AI patterns** — scan for the patterns listed below.
2. **Rewrite problematic sections** — replace AI-isms with natural alternatives.
3. **Preserve meaning** — keep the core message intact.
4. **Maintain voice** — match the intended tone. If a voice sample was provided, match it.
5. **Add soul** — don't just remove bad patterns, inject actual personality.
6. **Final anti-AI pass** — ask: "What makes this obviously AI generated?" Fix remaining tells.

## Voice Calibration (optional)

If the user provides a writing sample, analyze it first:
- Sentence length patterns, word choice level, paragraph openings
- Punctuation habits, recurring phrases, transition style

Match their voice in the rewrite. When no sample is provided, use natural, varied, opinionated voice.

## Personality and Soul

Avoiding AI patterns is only half the job. Sterile, voiceless writing is just as obvious.

**Have opinions.** React to facts. **Vary your rhythm.** Short punchy, then longer. **Acknowledge complexity.** Real humans have mixed feelings. **Use "I" when it fits.** **Let some mess in.** **Be specific about feelings.**

## Content Patterns to Fix

### 1. Undue Emphasis on Significance
**Watch for:** stands/serves as, is a testament, a vital/significant/crucial/pivotal role, underscores importance, reflects broader, setting the stage for, indelible mark

### 2. Undue Emphasis on Notability
**Watch for:** independent coverage, local/regional/national media outlets, active social media presence

### 3. Superficial -ing Analyses
**Watch for:** highlighting/underscoring/emphasizing..., ensuring..., reflecting/symbolizing..., showcasing...

### 4. Promotional Language
**Watch for:** boasts a, vibrant, rich, profound, nestled, in the heart of, groundbreaking, renowned, breathtaking, stunning

### 5. Vague Attributions
**Watch for:** Industry reports, Observers have cited, Experts argue, Some critics argue

### 6. Formulaic "Challenges and Future Prospects"
**Watch for:** Despite its... faces several challenges..., Future Outlook

### 7. Overused AI Vocabulary
**High-frequency:** delve, crucial, enhance, fostering, interplay, intricate, landscape (abstract), pivotal, tapestry (abstract), testament, underscore, vibrant

### 8. Copula Avoidance
**Watch for:** serves as/stands as/marks/represents instead of simple "is"

## Language Fixes

- Replace elaborate constructs with simple "is/are"
- Cut adverb padding ("significantly", "notably", "remarkably")
- Remove hedge-then-assert patterns ("While X, it is important to note that...")
- Kill list-of-three rhythm ("X, Y, and Z" repeated across paragraphs)
- Avoid starting sentences with "Additionally", "Furthermore", "Moreover"
- Don't capitalize concepts unnecessarily
- Use concrete specifics over vague claims
