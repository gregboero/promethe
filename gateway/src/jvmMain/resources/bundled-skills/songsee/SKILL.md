---
name: songsee
description: "Audio spectrograms/features (mel, chroma, MFCC) via CLI."
source: BUNDLED
requires_cli: songsee
platforms: jvm
---

# songsee

Generate spectrograms and multi-panel audio feature visualizations from audio files.

## Prerequisites

Requires [Go](https://go.dev/doc/install):

```
execute_command(command="go", args=["install", "github.com/steipete/songsee/cmd/songsee@latest"])
```

Optional: `ffmpeg` for formats beyond WAV/MP3.

## Quick Start

```
# Basic spectrogram
execute_command(command="songsee", args=["track.mp3"])

# Save to specific file
execute_command(command="songsee", args=["track.mp3", "-o", "spectrogram.png"])

# Multi-panel visualization grid
execute_command(command="songsee", args=["track.mp3", "--viz", "spectrogram,mel,chroma,hpss,selfsim,loudness,tempogram,mfcc,flux"])

# Time slice (start at 12.5s, 8s duration)
execute_command(command="songsee", args=["track.mp3", "--start", "12.5", "--duration", "8", "-o", "slice.jpg"])
```

## Visualization Types

Use `--viz` with comma-separated values:

| Type | Description |
|------|-------------|
| `spectrogram` | Standard frequency spectrogram |
| `mel` | Mel-scaled spectrogram (perceptual frequency) |
| `chroma` | Chromagram (pitch classes, harmonic content) |
| `hpss` | Harmonic-percussive source separation |
| `selfsim` | Self-similarity matrix (structural analysis) |
| `loudness` | Loudness over time |
| `tempogram` | Tempo estimation over time |
| `mfcc` | Mel-frequency cepstral coefficients (timbre) |
| `flux` | Spectral flux (onset detection) |

## Common Workflows

### Analyze a track's structure
```
execute_command(command="songsee", args=["track.mp3", "--viz", "selfsim,chroma,tempogram", "-o", "structure.png"])
```

### Compare audio segments
```
execute_command(command="songsee", args=["track.mp3", "--start", "0", "--duration", "30", "-o", "intro.png"])
execute_command(command="songsee", args=["track.mp3", "--start", "60", "--duration", "30", "-o", "chorus.png"])
```

### Full analysis grid
```
execute_command(command="songsee", args=["track.mp3", "--viz", "spectrogram,mel,chroma,hpss,selfsim,loudness,tempogram,mfcc,flux", "-o", "full_analysis.png"])
```
