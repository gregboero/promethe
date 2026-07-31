---
name: ascii-video
description: "ASCII video: convert video/audio to colored ASCII MP4/GIF."
source: BUNDLED
requires_cli: python3,ffmpeg
platforms: jvm
---

# ASCII Video Production Pipeline

## When to use

Use when users request: ASCII video, text art video, terminal-style video, character art animation, retro text visualization, audio visualizer in ASCII, converting video to ASCII art, matrix-style effects, or any animated ASCII output.

## What's inside

Production pipeline for ASCII art video — any format. Converts video/audio/images/generative input into colored ASCII character video output (MP4, GIF, image sequence). Covers: video-to-ASCII conversion, audio-reactive music visualizers, generative ASCII art animations, hybrid video+audio reactive, text/lyrics overlays, real-time terminal rendering.

## Creative Standard

This is visual art. ASCII characters are the medium; cinema is the standard.

**Before writing a single line of code**, articulate the creative concept. What is the mood? What visual story does this tell? What makes THIS project different from every other ASCII video?

**First-render excellence is non-negotiable.** The output must be visually striking without requiring revision rounds.

**Be proactively creative.** Include at least one visual moment the user didn't ask for but will appreciate — a transition, an effect, a color choice that elevates the whole piece.

**Cohesive aesthetic over technical correctness.** All scenes in a video must feel connected by a unifying visual language — shared color temperature, related character palettes, consistent motion vocabulary.

**Dense, layered, considered.** Every frame should reward viewing. Never flat black backgrounds. Always multi-grid composition. Always per-scene variation. Always intentional color.

## Modes

| Mode | Input | Output |
|------|-------|--------|
| **Video-to-ASCII** | Video file | ASCII recreation of source footage |
| **Audio-reactive** | Audio file | Generative visuals driven by audio features |
| **Generative** | None (or seed params) | Procedural ASCII animation |
| **Hybrid** | Video + audio | ASCII video with audio-reactive overlays |
| **Lyrics/text** | Audio + text/SRT | Timed text with visual effects |
| **TTS narration** | Text quotes + TTS API | Narrated testimonial/quote video with typed text |

## Stack

Single self-contained Python script per project. No GPU required.

| Layer | Tool | Purpose |
|-------|------|---------|
| Core | Python 3.10+, NumPy | Math, array ops, vectorized effects |
| Signal | SciPy | FFT, peak detection (audio modes) |
| Imaging | Pillow (PIL) | Font rasterization, frame decoding, image I/O |
| Video I/O | ffmpeg (CLI) | Decode input, encode output, mux audio |
| Parallel | concurrent.futures | N workers for batch/clip rendering |
| Optional | OpenCV | Video frame sampling, edge detection |

## Pipeline Architecture

Every mode follows the same 6-stage pipeline:

```
INPUT → ANALYZE → SCENE_FN → TONEMAP → SHADE → ENCODE
```

1. **INPUT** — Load/decode source material (video frames, audio samples, images, or nothing)
2. **ANALYZE** — Extract per-frame features (audio bands, video luminance/edges, motion vectors)
3. **SCENE_FN** — Scene function renders to pixel canvas (`uint8 H,W,3`). Composes multiple character grids via `_render_vf()` + pixel blend modes
4. **TONEMAP** — Percentile-based adaptive brightness normalization
5. **SHADE** — Post-processing via `ShaderChain` + `FeedbackBuffer`
6. **ENCODE** — Pipe raw RGB frames to ffmpeg for H.264/GIF encoding

## Workflow

1. Write the Python script: `file_write(path="ascii_video.py", content=...)`
2. Run it: `execute_command(command="python3", args=["ascii_video.py"])`
3. Present output files to the user

## Aesthetic Dimensions

| Dimension | Options |
|-----------|---------|
| **Character palette** | Density ramps, block elements, symbols, scripts (katakana, Greek, runes, braille) |
| **Color strategy** | HSV, OKLAB/OKLCH, discrete RGB palettes, monochrome, temperature |
| **Background texture** | Sine fields, fBM noise, domain warp, voronoi, reaction-diffusion |
| **Primary effects** | Rings, spirals, tunnel, vortex, waves, interference, aurora, fire, SDFs |
| **Particles** | Sparks, snow, rain, bubbles, runes, orbits, flocking boids |
| **Shader mood** | Retro CRT, clean modern, glitch art, cinematic, dreamy, psychedelic |
| **Grid density** | xs(8px) through xxl(40px), mixed per layer |
| **Feedback** | Zoom tunnel, rainbow trails, ghostly echo, rotating mandala |
| **Transitions** | Crossfade, wipe, dissolve, glitch cut, iris, mask-based reveal |

### Per-Section Variation

Never use the same config for the entire video. For each section/scene:
- **Different background effect** (or compose 2-3)
- **Different character palette** (match the mood)
- **Different color temperature** (warm/cool shifts across sections)
- **At least one unique visual moment** per scene
