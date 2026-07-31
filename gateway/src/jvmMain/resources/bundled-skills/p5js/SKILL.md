---
name: p5js
description: "p5.js sketches: gen art, shaders, interactive, 3D."
source: BUNDLED
platforms: jvm
---

# p5.js Production Pipeline

## When to use

Use when users request: p5.js sketches, creative coding, generative art, interactive visualizations, canvas animations, browser-based visual art, data viz, shader effects, or any p5.js project.

## What's inside

Production pipeline for interactive and generative visual art using p5.js. Creates browser-based sketches, generative art, data visualizations, interactive experiences, 3D scenes, audio-reactive visuals, and motion graphics — exported as HTML, PNG, GIF, MP4, or SVG.

## Creative Standard

This is visual art rendered in the browser. The canvas is the medium; the algorithm is the brush.

**Before writing a single line of code**, articulate the creative concept. What does this piece communicate? What makes the viewer stop scrolling?

**First-render excellence is non-negotiable.** The output must be visually striking on first load.

**Go beyond the reference vocabulary.** Combine, layer, and invent. The catalog is a palette of paints — you write the painting.

**Be proactively creative.** If the user asks for "a particle system," deliver one with emergent flocking behavior, trailing ghost echoes, palette-shifted depth fog, and a breathing background noise field.

**Dense, layered, considered.** Every frame should reward viewing. Never flat white backgrounds. Always compositional hierarchy.

**Cohesive aesthetic over feature count.** All elements must serve a unified visual language.

## Modes

| Mode | Input | Output |
|------|-------|--------|
| **Generative art** | Seed / parameters | Procedural visual composition |
| **Data visualization** | Dataset / API | Interactive charts, custom data displays |
| **Interactive experience** | None (user drives) | Mouse/keyboard/touch-driven sketch |
| **Animation** | Timeline / storyboard | Kinetic typography, transitions |
| **3D scene** | Concept description | WebGL geometry, lighting, camera |
| **Image processing** | Image file(s) | Pixel manipulation, filters, mosaic |
| **Audio-reactive** | Audio file / mic | Sound-driven generative visuals |

## Stack

Single self-contained HTML file per project. No build step required.

| Layer | Tool | Purpose |
|-------|------|---------|
| Core | p5.js 1.11.3 (CDN) | Canvas rendering, math, transforms |
| 3D | p5.js WebGL mode | 3D geometry, camera, lighting, GLSL shaders |
| Audio | p5.sound.js (CDN) | FFT analysis, amplitude, mic input |
| Export | Built-in `saveCanvas()` / `saveGif()` | PNG, GIF output |
| Capture | CCapture.js (optional) | Deterministic framerate video capture |
| SVG | p5.js-svg 1.6.0 (optional) | Vector output for print |

### Version Note

**p5.js 1.x** (1.11.3) is the default. **p5.js 2.x** (2.2+) adds: `async setup()`, OKLCH/OKLAB color modes, shader `.modify()` API, variable fonts. Use 2.x only when needed.

## Pipeline

```
CONCEPT → DESIGN → CODE → PREVIEW → EXPORT → VERIFY
```

1. **CONCEPT** — Articulate mood, color world, motion vocabulary, uniqueness
2. **DESIGN** — Choose mode, canvas size, interaction model, color system, export format
3. **CODE** — Write single HTML file: `file_write(path="sketch.html", content=...)`
4. **PREVIEW** — Open in browser: `execute_command(command="open", args=["sketch.html"])`
5. **EXPORT** — Capture output with `saveCanvas()` / `saveGif()` / ffmpeg
6. **VERIFY** — Does the output match the concept? Is it visually striking?

## Aesthetic Dimensions

| Dimension | Options |
|-----------|---------|
| **Color system** | HSB/HSL, RGB, named palettes, procedural harmony, gradient interpolation |
| **Noise vocabulary** | Perlin, simplex, fractal (octaved), domain warping, curl noise |
| **Particle systems** | Physics-based, flocking, trail-drawing, attractor-driven, flow-field |
| **Shape language** | Geometric primitives, custom vertices, bezier curves, SVG paths |
| **Motion style** | Eased, spring-based, noise-driven, physics sim, lerped |
| **Typography** | System fonts, loaded OTF, `textToPoints()` particle text, kinetic |
| **Shader effects** | GLSL fragment/vertex, filter shaders, post-processing, feedback loops |
| **Composition** | Grid, radial, golden ratio, rule of thirds, organic scatter, tiled |
| **Interaction** | Mouse follow, click spawn, drag, keyboard state, scroll-driven, mic |
| **Blend modes** | `BLEND`, `ADD`, `MULTIPLY`, `SCREEN`, `DIFFERENCE`, `OVERLAY` |
| **Layering** | `createGraphics()` offscreen buffers, alpha compositing, masking |

## Per-Project Rules

Never use default configurations. For every project:
- **Custom color palette** — never raw `fill(255, 0, 0)`. Always 3-7 designed colors
- **Custom stroke weight vocabulary** — thin (0.5), medium (1-2), bold (3-5)
- **Background treatment** — never plain `background(0)`. Always textured or layered
- **Motion variety** — different speeds for different elements
- **At least one invented element** — a custom behavior the user didn't request

## Parameter Design

Parameters should emerge from the algorithm. Ask: "What properties of *this* system should be tunable?"

**Good parameters:** quantities (density), scales (texture), rates (energy), thresholds (drama), ratios (harmony).

**Bad parameters:** generic "color1", "size" — meaningless without algorithmic context.
