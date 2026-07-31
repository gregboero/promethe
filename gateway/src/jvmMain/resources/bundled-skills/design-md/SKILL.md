---
name: design-md
description: "Author/validate/export Google's DESIGN.md token spec files."
source: BUNDLED
requires_cli: npx
platforms: jvm
---

# DESIGN.md Skill

DESIGN.md is Google's open spec (Apache-2.0, `google-labs-code/design.md`) for describing a visual identity to coding agents. One file combines:

- **YAML front matter** — machine-readable design tokens (normative values)
- **Markdown body** — human-readable rationale, organized into canonical sections

Tokens give exact values. Prose tells agents *why* those values exist and how to apply them. The CLI (`npx @google/design.md`) lints structure + WCAG contrast, diffs versions for regressions, and exports to Tailwind or W3C DTCG JSON.

## When to use this skill

- User asks for a DESIGN.md file, design tokens, or a design system spec
- User wants consistent UI/brand across multiple projects or tools
- User pastes an existing DESIGN.md and asks to lint, diff, export, or extend it
- User asks to port a style guide into a format agents can consume
- User wants contrast / WCAG accessibility validation on their color palette

For visual inspiration or layout examples, use `popular-web-designs`. For designing a one-off HTML artifact (prototype, deck, landing page), use `claude-design`. This skill is for the *formal spec file* itself.

## File anatomy

```md
---
version: alpha
name: Heritage
description: Architectural minimalism meets journalistic gravitas.
colors:
  primary: "#1A1C1E"
  secondary: "#6C7278"
  tertiary: "#B8422E"
  neutral: "#F7F5F2"
typography:
  h1:
    fontFamily: Public Sans
    fontSize: 3rem
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: "-0.02em"
  body-md:
    fontFamily: Public Sans
    fontSize: 1rem
rounded:
  sm: 4px
  md: 8px
  lg: 16px
spacing:
  sm: 8px
  md: 16px
  lg: 24px
components:
  button-primary:
    backgroundColor: "{colors.tertiary}"
    textColor: "#FFFFFF"
    rounded: "{rounded.sm}"
    padding: 12px
  button-primary-hover:
    backgroundColor: "{colors.primary}"
---

## Overview

Architectural Minimalism meets Journalistic Gravitas...

## Colors

- **Primary (#1A1C1E):** Deep ink for headlines and core text.
- **Tertiary (#B8422E):** "Boston Clay" — the sole driver for interaction.

## Typography

Public Sans for everything except small all-caps labels...

## Components

`button-primary` is the only high-emphasis action on a page...
```

## Token types

| Type | Format | Example |
|------|--------|---------|
| Color | `#` + hex (sRGB) | `"#1A1C1E"` |
| Dimension | number + unit (`px`, `em`, `rem`) | `48px`, `-0.02em` |
| Token reference | `{path.to.token}` | `{colors.primary}` |
| Typography | object with `fontFamily`, `fontSize`, `fontWeight`, `lineHeight`, `letterSpacing` | see above |

Component property whitelist: `backgroundColor`, `textColor`, `typography`, `rounded`, `padding`, `size`, `height`, `width`. Variants (hover, active, pressed) are separate components suffixed with the state name.

## Workflow

### Author a new DESIGN.md

1. Gather brand context: `file_read(path=...)` for existing styles, screenshots, or brand docs
2. Write the file: `file_write(path="DESIGN.md", content=...)`
3. Lint it: `execute_command(command="npx", args=["@google/design.md", "lint", "DESIGN.md"])`

### Validate an existing file

```
execute_command(command="npx", args=["@google/design.md", "lint", "DESIGN.md"])
```

### Diff two versions

```
execute_command(command="npx", args=["@google/design.md", "diff", "old.md", "new.md"])
```

### Export tokens

To Tailwind:
```
execute_command(command="npx", args=["@google/design.md", "export", "--format", "tailwind", "DESIGN.md"])
```

To W3C DTCG JSON:
```
execute_command(command="npx", args=["@google/design.md", "export", "--format", "dtcg", "DESIGN.md"])
```

## WCAG Contrast Checking

The lint command automatically checks all color combinations for WCAG AA compliance. Fix any warnings by adjusting lightness or choosing adjacent palette colors.
