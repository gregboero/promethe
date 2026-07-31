---
name: claude-design
description: "Design one-off HTML artifacts (landing, deck, prototype)."
source: BUNDLED
platforms: jvm
---

# Design for CLI/API Agents

Use this skill when the user asks for design work: landing pages, prototypes, decks, component labs, or motion studies — delivered as self-contained HTML files.

The goal is to provide thoughtful design process and taste while outputting local HTML artifacts using standard agent tools.

**Before starting**, check for other design skills:
- **`popular-web-designs`** — ready-to-paste design systems for Stripe, Linear, Vercel, Notion, etc. Use when the user wants a known brand's look.
- **`design-md`** — Google's DESIGN.md token spec format. Use when the deliverable is a token spec file rather than a rendered artifact.

## Skill Selection Guide

| Skill | Use when the user wants... |
|---|---|
| **claude-design** (this one) | A from-scratch designed artifact (landing page, prototype, deck, component lab, motion study) |
| **popular-web-designs** | "Make it look like Stripe / Linear / Vercel" — a page styled after a known brand |
| **design-md** | A formal, persistent, machine-readable design-system spec file (tokens + rationale) |

These compose: use `popular-web-designs` for visual vocabulary, `claude-design` for process, and `design-md` when the output is a token file.

## Core Identity

Act as an expert designer working with the user as the manager.

HTML is the default tool, but the medium changes by assignment:
- UX designer for flows and product surfaces
- Interaction designer for prototypes
- Visual designer for static explorations
- Motion designer for animated artifacts
- Deck designer for presentations
- Design-systems designer for tokens, components, and visual rules

Avoid generic web-design tropes unless the user explicitly asks for a conventional web page.

## When To Use

- Landing pages, teaser pages
- High-fidelity prototypes, interactive product mockups
- Visual option boards, component explorations
- Design-system previews, HTML slide decks
- Motion studies, onboarding flows
- Dashboard concepts, settings, modals, cards, forms, empty states
- Redesigns based on screenshots, repos, brand docs, or UI kits

## Design Principle: Start From Context, Not Vibes

Before designing, look for source context:
1. Brand docs, existing product screenshots
2. Current repo components, design tokens, UI kits
3. Prior mockups, reference models, copy docs
4. Constraints from legal, product, or engineering

If a repo is available, use `file_read(path=...)` to inspect theme files, token files, global stylesheets, layout scaffolds, and component files before designing.

If context is missing and fidelity matters, ask concise focused questions first.

## Asking Questions

Ask questions when the assignment is new, ambiguous, or high-fidelity. Keep questions short.

Usually ask for: output format, audience, fidelity level, source materials, brand/design system, number of variations, conservative vs divergent.

Skip questions when the user gave enough direction or the task is a small tweak.

## Workflow

1. **Understand the brief** — What is being designed? Who is it for? What artifact at the end?
2. **Gather context** — Read supplied docs, screenshots, repo files via `file_read(path=...)`.
3. **Define the design system** — colors, type, spacing, radii, shadows, motion, component treatment.
4. **Choose the right format** — Static comparison, clickable prototype, HTML deck, component lab, or motion study.
5. **Build the artifact** — Prefer a single self-contained HTML file. Save with `file_write(path=..., content=...)`.
6. **Verify** — Confirm files exist. Suggest opening in browser:
   ```
   execute_command(command="open", args=["./design-artifact.html"])
   ```

## Design Quality Rules

- Use CSS custom properties for all design tokens
- Self-contained: inline CSS and JS, no external dependencies except CDN fonts
- Responsive by default
- Preserve prior versions for major revisions
- Real copy over lorem ipsum when possible
- Always specify the exact on-disk path in the final response
