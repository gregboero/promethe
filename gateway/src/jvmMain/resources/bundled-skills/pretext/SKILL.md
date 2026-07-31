---
name: pretext
description: "Creative browser demos with @chenglou/pretext — DOM-free text layout for ASCII art, typographic flow, kinetic typography, and text-powered generative art."
source: BUNDLED
platforms: jvm
---

# Pretext Creative Demos

## Overview

[`@chenglou/pretext`](https://github.com/chenglou/pretext) is a 15KB zero-dependency TypeScript library by Cheng Lou for **DOM-free multiline text measurement and layout**. Given `(text, font, width)`, it returns line breaks, per-line widths, per-grapheme positions, and total height — all via canvas measurement, no reflow.

Because it is fast and geometric, it is a **creative primitive**: reflow paragraphs around a moving sprite at 60fps, build games whose level geometry is made of real words, shatter text into particles with exact per-grapheme positions, or pack shrink-wrapped multiline UI without any `getBoundingClientRect` thrash.

## When to Use

Use when the user asks for:
- A "pretext demo" / "cool pretext thing" / "text-as-X"
- Text flowing around a moving shape (hero sections, editorial layouts)
- ASCII-art effects using **real words or prose**, not monospace rasters
- Games where the playfield is made of text (Tetris-from-letters, Breakout-of-prose)
- Kinetic typography with per-glyph physics (shatter, scatter, flock, flow)
- Typographic generative art, especially with non-Latin or mixed scripts
- Multiline "shrink-wrap" UI (smallest container width that still fits text)

Don't use for:
- Static SVG/HTML pages where CSS solves layout — just use CSS
- Rich text editors (pretext is intentionally narrow)
- Image → text (use `ascii-video` skill)
- Pure canvas generative art with no text role — use `p5js`

## Creative Standard

- **Don't ship a "hello world" demo.** Every delivered demo must add intentional color, motion, composition.
- **Dark backgrounds, warm cores, considered palette.** Amber-on-black, cold-white-on-charcoal, or desaturated pastels.
- **Proportional fonts are the point.** Use Iowan Old Style, Inter, JetBrains Mono, Helvetica Neue, or a variable font.
- **Real text, not lorem ipsum.** Manifestos, poetry, source code, found text.
- **First-paint excellence.** No loading states, no blank frames.

## Stack

Single self-contained HTML file per demo. No build step.

| Layer | Tool | Purpose |
|-------|------|---------|
| Core | `@chenglou/pretext` via `esm.sh` CDN | Text measurement + line layout |
| Render | HTML5 Canvas 2D | Glyph rendering, per-frame composition |
| Segmentation | `Intl.Segmenter` (built-in) | Grapheme splitting for emoji / CJK |
| Interaction | Raw DOM events | Mouse / touch / wheel — no framework |

```html
<script type="module">
import {
  prepare, layout,
  prepareWithSegments, layoutWithLines,
  layoutNextLineRange, materializeLineRange,
  measureLineStats, walkLineRanges,
} from "https://esm.sh/@chenglou/pretext@0.0.6";
</script>
```

## The Two Use Cases

### Use-case 1 — measure, then render with CSS/DOM

```js
const prepared = prepare(text, "16px Inter");
const { height, lineCount } = layout(prepared, 320, 20);
```

Pretext tells you box height at a given width without a DOM read. Use for: virtualized lists, masonry heights, "does this label fit?" checks, preventing layout shift.

### Use-case 2 — measure *and* render yourself

```js
const prepared = prepareWithSegments(text, FONT);
const { lines } = layoutWithLines(prepared, 320, 26);
for (let i = 0; i < lines.length; i++) {
  ctx.fillText(lines[i].text, 0, i * 26);
}
```

This is where the creative work lives. You own the drawing:
- Render to canvas, SVG, WebGL, or any coordinate system
- Substitute per-glyph transforms (rotation, jitter, scale, opacity)
- Use line metadata (width, grapheme positions) as geometry

For **variable-width-per-line** flow (text around a shape):

```js
let cursor = { segmentIndex: 0, graphemeIndex: 0 };
let y = 0;
while (true) {
  const lineWidth = widthAtY(y);
  const range = layoutNextLineRange(prepared, cursor, lineWidth);
  if (!range) break;
  const line = materializeLineRange(prepared, range);
  ctx.fillText(line.text, leftEdgeAtY(y), y);
  cursor = range.nextCursor;
  y += lineHeight;
}
```

## Workflow

1. Articulate the creative concept — mood, motion, visual story
2. Write the HTML demo: `file_write(path="pretext-demo.html", content=...)`
3. Open for preview: `execute_command(command="open", args=["pretext-demo.html"])`
4. Iterate until the demo is visually striking at first paint
