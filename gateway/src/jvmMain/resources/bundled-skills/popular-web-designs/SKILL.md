---
name: popular-web-designs
description: "54 real design systems (Stripe, Linear, Vercel) as HTML/CSS."
source: BUNDLED
platforms: jvm
---

# Popular Web Designs

54 real-world design systems ready for use when generating HTML/CSS. Each template captures a site's complete visual language: color palette, typography hierarchy, component styles, spacing system, shadows, responsive behavior, and practical agent prompts with exact CSS values.

## Related design skills

- **`claude-design`** — use for the design *process and taste* (scoping a brief, producing variants, verifying a local HTML artifact). Pair with this skill when the user wants a page styled after a known brand.
- **`design-md`** — use when the deliverable is a formal DESIGN.md token spec file, not a rendered artifact.

## How to Use

1. Pick a design from the catalog below
2. Load the template from `templates/<site>.md`
3. Use the design tokens and component specs when generating HTML
4. Save the result with `file_write(path=..., content=...)`

Each template includes:
- CDN font substitute and Google Fonts `<link>` tag (ready to paste)
- CSS font-family stacks for primary and monospace
- Complete color palette, typography scale, and component specs

## HTML Generation Pattern

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Page Title</title>
  <!-- Paste the Google Fonts <link> from the template -->
  <link href="https://fonts.googleapis.com/css2?family=..." rel="stylesheet">
  <style>
    :root {
      --color-bg: #ffffff;
      --color-text: #171717;
      --color-accent: #533afd;
    }
    body {
      font-family: 'Inter', system-ui, sans-serif;
      color: var(--color-text);
      background: var(--color-bg);
    }
  </style>
</head>
<body>
  <!-- Build using component specs from the template -->
</body>
</html>
```

Write the file with `file_write(path=..., content=...)` and suggest opening in browser:
```
execute_command(command="open", args=["./page.html"])
```

## Font Substitution Reference

Most sites use proprietary fonts unavailable via CDN. Common mappings:

| Proprietary Font | CDN Substitute | Character |
|---|---|---|
| Geist / Geist Sans | Geist (Google Fonts) | Geometric, compressed tracking |
| Geist Mono | Geist Mono (Google Fonts) | Clean monospace, ligatures |
| sohne-var (Stripe) | Source Sans 3 | Light weight elegance |
| Berkeley Mono | JetBrains Mono | Technical monospace |
| Airbnb Cereal VF | DM Sans | Rounded, friendly geometric |
| Circular (Spotify) | DM Sans | Geometric, warm |
| figmaSans | Inter | Clean humanist |
| Pin Sans (Pinterest) | DM Sans | Friendly, rounded |
| CoinbaseDisplay/Sans | DM Sans | Geometric, trustworthy |
| UberMove | DM Sans | Bold, tight |
| HashiCorp Sans | Inter | Enterprise, neutral |

## Template Catalog

Templates are organized in `templates/` directory. Each `.md` file contains the complete design system for one site, including:

1. **Color Palette** — All colors as hex/rgba with semantic names
2. **Typography** — Font stacks, sizes, weights, line heights
3. **Spacing** — Padding/margin scale
4. **Components** — Buttons, cards, inputs, navigation with exact CSS
5. **Layout** — Grid systems, breakpoints, max-widths
6. **Shadows & Effects** — Box shadows, borders, radii
7. **Agent Prompt** — Ready-to-use prompt with all CSS values inline

## Workflow

1. User requests a page styled like a specific brand
2. Load the template: `file_read(path="templates/<brand>.md")`
3. Extract design tokens and component specs
4. Generate HTML using the template's CSS values
5. Save: `file_write(path="page.html", content=...)`
6. Verify visual accuracy against the template specs
