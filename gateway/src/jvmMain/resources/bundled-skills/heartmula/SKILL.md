---
name: heartmula
description: "HeartMuLa: Suno-like song generation from lyrics + tags."
source: BUNDLED
requires_cli: python3,git
platforms: jvm
---

# HeartMuLa — Open-Source Music Generation

## Overview

HeartMuLa is a family of open-source music foundation models (Apache-2.0) that generates music conditioned on lyrics and tags, with multilingual support. Comparable to Suno for open-source. Includes:
- **HeartMuLa** — Music language model (3B/7B) for generation from lyrics + tags
- **HeartCodec** — 12.5Hz music codec for high-fidelity audio reconstruction
- **HeartTranscriptor** — Whisper-based lyrics transcription
- **HeartCLAP** — Audio-text alignment model

## When to Use

- User wants to generate music/songs from text descriptions
- User wants an open-source Suno alternative
- User wants local/offline music generation
- User asks about HeartMuLa, heartlib, or AI music generation

## Hardware Requirements

- **Minimum**: 8GB VRAM with `--lazy_load true` (loads/unloads models sequentially)
- **Recommended**: 16GB+ VRAM for comfortable single-GPU usage
- **Multi-GPU**: Use `--mula_device cuda:0 --codec_device cuda:1` to split across GPUs
- 3B model with lazy_load peaks at ~6.2GB VRAM

## Installation Steps

### 1. Clone Repository

```
execute_command(command="git", args=["clone", "https://github.com/HeartMuLa/heartlib.git"])
```

### 2. Create Virtual Environment (Python 3.10 required)

```
execute_command(command="uv", args=["venv", "--python", "3.10", ".venv"])
execute_command(command="uv", args=["pip", "install", "-e", "."])
```

### 3. Fix Dependency Compatibility Issues

**IMPORTANT**: As of Feb 2026, the pinned dependencies have conflicts with newer packages:

```
execute_command(command="uv", args=["pip", "install", "--upgrade", "datasets"])
execute_command(command="uv", args=["pip", "install", "--upgrade", "transformers"])
```

### 4. Patch Source Code (Required for transformers 5.x)

**Patch 1 — RoPE cache fix** in `src/heartlib/heartmula/modeling_heartmula.py`:

In the `setup_caches` method of the `HeartMuLa` class, add RoPE reinitialization after the `reset_caches` try/except block and before the `with device:` block:

```python
# Re-initialize RoPE caches that were skipped during meta-device loading
from torchtune.models.llama3_1._position_embeddings import Llama3ScaledRoPE
for module in self.modules():
    if isinstance(module, Llama3ScaledRoPE) and not module.is_cache_built:
        module.rope_init()
        module.to(device)
```

**Why**: `from_pretrained` creates model on meta device first; `Llama3ScaledRoPE.rope_init()` skips cache building on meta tensors, then never rebuilds after weights are loaded to real device.

**Patch 2 — HeartCodec loading fix** in `src/heartlib/pipelines/music_generation.py`:

Add `ignore_mismatched_sizes=True` to ALL `HeartCodec.from_pretrained()` calls (there are 2: the eager load in `__init__` and the lazy load in the `codec` property).

Use `file_read` to inspect the files and `file_write` to apply patches.

## Generation

```
execute_command(command="python3", args=["-m", "heartlib.generate",
    "--lyrics", "Your lyrics here",
    "--tags", "pop, upbeat, female vocals",
    "--output", "song.wav",
    "--lazy_load", "true"])
```
