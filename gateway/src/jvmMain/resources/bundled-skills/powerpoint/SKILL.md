---
name: powerpoint
description: "Create, read, edit .pptx decks, slides, notes, templates."
source: BUNDLED
requires_cli: python3
platforms: jvm
---

# PowerPoint Skill

## When to Use

Use this skill any time a .pptx file is involved — as input, output, or both. This includes: creating slide decks, pitch decks, or presentations; reading, parsing, or extracting text from any .pptx file; editing, modifying, or updating existing presentations; combining or splitting slide files; working with templates, layouts, speaker notes, or comments. Trigger whenever the user mentions "deck," "slides," "presentation," or references a .pptx filename.

## Quick Reference

| Task | Method |
|------|--------|
| Read/analyze content | `execute_command(command="python3", args=["-m", "markitdown", "presentation.pptx"])` |
| Edit or create from template | Use python-pptx via execute_command |
| Create from scratch | Use pptxgenjs via execute_command |

## Reading Content

```
# Text extraction
execute_command(command="python3", args=["-m", "markitdown", "presentation.pptx"])

# Visual overview / thumbnails
execute_command(command="python3", args=["scripts/thumbnail.py", "presentation.pptx"])
```

## Editing Workflow

1. Analyze template with `thumbnail.py`
2. Unpack → manipulate slides → edit content → clean → pack

## Creating from Scratch

Use when no template or reference presentation is available. Generate via pptxgenjs or python-pptx.

## Design Ideas

**Don't create boring slides.** Plain bullets on a white background won't impress anyone.

### Before Starting

- **Pick a bold, content-informed color palette**: The palette should feel designed for THIS topic.
- **Dominance over equality**: One color should dominate (60-70% visual weight), with 1-2 supporting tones and one sharp accent.
- **Dark/light contrast**: Dark backgrounds for title + conclusion slides, light for content.
- **Commit to a visual motif**: Pick ONE distinctive element and repeat it across every slide.

### Color Palettes

| Theme | Primary | Secondary | Accent |
|-------|---------|-----------|--------|
| **Midnight Executive** | `1E2761` (navy) | `CADCFC` (ice blue) | `FFFFFF` (white) |
| **Forest & Moss** | `2C5F2D` (forest) | `97BC62` (moss) | `F5F5F5` (cream) |
| **Coral Energy** | `F96167` (coral) | `F9E795` (gold) | `2F3C7E` (navy) |
| **Warm Terracotta** | `B85042` (terracotta) | `E7E8D1` (sand) | `A7BEAE` (sage) |
| **Ocean Gradient** | `065A82` (deep blue) | `1C7293` (teal) | `21295C` (midnight) |
| **Charcoal Minimal** | `36454F` (charcoal) | `F2F2F2` (off-white) | `212121` (black) |
| **Teal Trust** | `028090` (teal) | `00A896` (seafoam) | `02C39A` (mint) |
| **Berry & Cream** | `6D2E46` (berry) | `A26769` (dusty rose) | `ECE2D0` (cream) |
| **Sage Calm** | `84B59F` (sage) | `69A297` (eucalyptus) | `50808E` (slate) |
| **Cherry Bold** | `990011` (cherry) | `FCF6F5` (off-white) | `2F3C7E` (navy) |

### For Each Slide

**Every slide needs a visual element** — image, chart, icon, or shape. Text-only slides are forgettable.

**Layout options:**
- Two-column (text left, illustration right)
- Icon + text rows (icon in colored circle, bold header, description below)
- 2x2 or 2x3 grid (image on one side, grid of content blocks on other)
- Half-bleed image (full left or right side) with content overlay

**Data display:**
- Large stat callouts (big numbers 60-72pt with small labels below)
- Comparison columns (before/after, pros/cons, side-by-side)
- Timeline or process flow (numbered steps, arrows)

**Visual polish:**
- Icons in colored circles for consistency
- Rounded image frames for a modern feel
- Subtle gradients on backgrounds
