# AI Capability Providers

> Reference for the ~28 AI capability providers (image generation, vision, embeddings,
> video generation, video analysis, TTS, STT) and how Promethé picks one per request.

## Overview

Promethé's agent tools (image generation, vision analysis, embeddings, video, text-to-speech,
speech-to-text) never hardcode a single AI vendor. Instead, each **capability** (e.g. "generate an
image") has a catalogue of possible **providers** (DALL-E 3, Imagen 3, Stability AI, ...), and the
provider actually used is decided at runtime based on which API keys the user has configured.

There are **two independent provider systems** in the codebase, split by ownership:

| System | Scope | Owner module |
|---|---|---|
| `ProviderRegistry` + `CapabilityRouter` | Image generation, Vision, Embeddings, Video generation, Video analysis | `shared` (agent tools) |
| `VoiceProviderRegistry` | Text-to-Speech, Speech-to-Text (+ Speech-to-Speech, Translate, not covered by this doc) | `gateway` (voice pipeline) |

Both follow the same idea — "declare all known providers, filter to the ones whose credentials are
present" — but they are separate classes with separate registries, since voice providers are
consumed by the gateway's real-time voice relay rather than discrete agent tools.

---

## Architecture

### ProviderRegistry

File: `promethe/shared/src/jvmMain/kotlin/dev/promethe/core/providers/ProviderRegistry.kt`

`ProviderRegistry` holds a hardcoded `List<ProviderEntry>` — the full catalogue of providers for
5 `Capability` values (`IMAGE_GENERATION`, `VISION`, `EMBEDDINGS`, `VIDEO_GENERATION`,
`VIDEO_ANALYSIS`). Each `ProviderEntry` carries:

```kotlin
data class ProviderEntry(
    val id: String,
    val name: String,
    val capability: Capability,
    val credentialKeys: List<String>,
    val isKoogNative: Boolean,
    val priority: Int = 0,
)
```

- **`credentialKeys`** — the keys that must be present and non-blank in the `apiKeys` map
  (constructor parameter) for the provider to count as configured.
- **`priority`** — lower value wins; used to pick a default when several providers are configured.
- **`isKoogNative`** — whether the provider is backed by Koog's native `LLMProvider` abstraction
  rather than a hand-rolled HTTP call.

Key methods:

- `isConfigured(provider)` — all `credentialKeys` present and non-blank.
- `getConfigured(capability)` — configured providers for a capability, sorted by priority.
- `getAll(capability)` — every registered provider for a capability, configured or not.
- `getDefault(capability)` — the lowest-priority configured provider, or `null`.
- `isAnyConfigured(capability)` — whether at least one provider is usable.

On construction it logs how many of the ~19 registered providers are configured.

### CapabilityRouter

File: `promethe/shared/src/jvmMain/kotlin/dev/promethe/core/providers/CapabilityRouter.kt`

`CapabilityRouter` wraps a `ProviderRegistry` and turns "which providers are configured for X" into
a `CapabilityResolution` for the agent or the UI:

```kotlin
sealed class CapabilityResolution {
    data class Ready(val provider: ProviderEntry, val confirmMessage: String)
    data class MultipleAvailable(val providers: List<ProviderEntry>, val choiceMessage: String)
    data class NotConfigured(
        val capability: Capability,
        val availableProviders: List<ProviderEntry>,
        val message: String,
    )
}
```

`resolve(capability)` logic:

1. Ask the registry for `getConfigured(capability)`.
2. **Zero configured** → `NotConfigured`, with a help message listing every registered provider and
   its required credential key(s), and a hint to use `config_set` or Settings > AI Media.
3. **Exactly one configured** → `Ready`, with a French confirmation message (e.g. *"Pour la
   génération d'images, j'ai DALL-E 3 (OpenAI) de configuré. Je l'utilise ?"*).
4. **More than one configured** → `MultipleAvailable`, with a numbered choice message, providers
   sorted by ascending priority.

Messages are in French to match the agent's conversational style (see
`dev.promethe.core.tools.builtin.ConfigTools`).

### VoiceProviderRegistry (TTS / STT)

File: `promethe/gateway/src/jvmMain/kotlin/dev/promethe/gateway/voice/VoiceProviderRegistry.kt`

TTS and STT are **not** routed through `CapabilityRouter`. They live in a separate,
gateway-owned registry keyed by `VoiceCapability` (`S2S`, `TTS`, `STT`, `TRANSLATE`). Each
`VoiceProvider` declares an `id`, `displayName`, a set of `capabilities`, a
`requiredSettingKey` (checked against DB settings merged with `~/.promethe/credentials.json`, DB
wins), and default model/voice lists (`defaultModels()`, `defaultVoices()`, overridable via
`fetchModels()`/`fetchVoices()` for providers that expose a live API).

`VoiceProviderRegistry.getAvailableProviders()` / `getProvidersByCapability()` filter
`allProviders()` down to those whose `requiredSettingKey` resolves to a non-blank value — the same
filter pattern as `ProviderRegistry`, but without a priority-based auto-selection step;
provider/voice/model are instead chosen explicitly by the caller (tool default, UI dropdown, or
request parameter).

---

## Complete provider table

All ids, credential keys, and models below are quoted directly from
`ProviderRegistry.kt` and `VoiceProviderRegistry.kt` — nothing here is inferred.

### Image Generation (`Capability.IMAGE_GENERATION`) — 4 providers

| Priority | id | name | credentialKeys | Koog-native | Model used (from tool implementation) |
|---|---|---|---|---|---|
| 0 | `openai-dalle3` | DALL-E 3 (OpenAI) | `openai` | true | `dall-e-3` |
| 1 | `google-imagen3` | Imagen 3 (Google) | `google` | true | `imagen-3.0-generate-002` |
| 2 | `stability-ai` | Stable Diffusion (Stability AI) | `stability` | false | Stability `v2beta/stable-image/generate/sd3` |
| 3 | `replicate-flux` | Flux (Replicate) | `replicate` | false | *(not implemented in `ImageGenerationTool` — no HTTP branch for this id)* |

### Vision (`Capability.VISION`) — 3 providers

| Priority | id | name | credentialKeys | Koog-native | Model used |
|---|---|---|---|---|---|
| 0 | `openai-vision` | GPT-4o Vision (OpenAI) | `openai` | true | `gpt-4o` |
| 1 | `google-gemini-vision` | Gemini Vision (Google) | `google` | true | `gemini-3.5-flash` |
| 2 | `anthropic-claude-vision` | Claude Vision (Anthropic) | `anthropic` | true | `claude-sonnet-4-20250514` |

### Embeddings (`Capability.EMBEDDINGS`) — 6 providers

| Priority | id | name | credentialKeys | Koog-native |
|---|---|---|---|---|
| 0 | `openai-embed` | text-embedding-3 (OpenAI) | `openai` | false |
| 1 | `google-embed` | Gemini Embeddings (Google) | `google` | false |
| 2 | `ollama-embed` | Ollama (Local) | `ollama_url` | false |
| 3 | `cohere-embed` | Cohere Embed | `cohere` | false |
| 4 | `voyage-embed` | Voyage AI | `voyage` | false |
| 5 | `mistral-embed` | Mistral Embed | `mistral` | false |

The actual model string is passed through to `EmbeddingServiceFactory.create()` (default
`"text-embedding-3-small"`, overridable per-call via `EmbeddingArgs.model`).

### Video Generation (`Capability.VIDEO_GENERATION`) — 4 providers

| Priority | id | name | credentialKeys | Koog-native | Model / status |
|---|---|---|---|---|---|
| 0 | `google-veo3` | Veo 3 (Google) | `google` | false | Not yet implemented — returns an `[INFO]` placeholder |
| 1 | `replicate-luma` | Luma Ray (Replicate) | `replicate` | false | `luma/ray` (Replicate `version` field) |
| 2 | `fal-minimax` | MiniMax (fal.ai) | `fal` | false | `fal-ai/minimax-video/image-to-video` |
| 3 | `runway-gen3` | Gen-3 Alpha (Runway) | `runway` | false | Runway `image_to_video` endpoint |

### Video Analysis (`Capability.VIDEO_ANALYSIS`) — 2 providers

| Priority | id | name | credentialKeys | Koog-native | Model / status |
|---|---|---|---|---|---|
| 0 | `google-gemini-video` | Gemini Video (Google) | `google` | true | `gemini-3.5-flash` |
| 1 | `openai-vision-frames` | GPT-4o Frames (OpenAI) | `openai` | true | Not yet implemented — returns an `[INFO]` placeholder |

**Subtotal (ProviderRegistry): 19 providers across 5 capabilities.**

### Text-to-Speech (`VoiceCapability.TTS`) — 4 providers

| id | displayName | requiredSettingKey | defaultModels() |
|---|---|---|---|
| `gemini_tts` | Google Gemini TTS | `GOOGLE_API_KEY` | `gemini-3.1-flash-tts-preview` |
| `elevenlabs` | ElevenLabs | `ELEVENLABS_API_KEY` | `eleven_v3`, `eleven_flash_v2_5`, `eleven_multilingual_v2` |
| `cartesia` | Cartesia | `CARTESIA_API_KEY` | `sonic-3.5`, `sonic-3` |
| `openai_tts` | OpenAI TTS | `OPENAI_API_KEY` | `tts-1`, `tts-1-hd` (note: `gpt-4o-mini-tts` is marked deprecated by OpenAI as of 2026 in a source comment) |

Note: `TtsSynthesizer` (`gateway/.../voice/TtsSynthesizer.kt`), used by the `/api/v1/tts/synthesize`
route the `text_to_speech` tool calls, only implements 3 of these end-to-end — `openai_tts` (model
`gpt-4o-mini-tts`, PCM 24kHz), `elevenlabs` (model `eleven_flash_v2_5`, PCM 16kHz), and `gemini_tts`
/ `gemini_live` (model `gemini-3.1-flash-tts-preview`, PCM 24kHz). `cartesia` is registered in
`VoiceProviderRegistry` but has no synthesis branch in `TtsSynthesizer`.

### Speech-to-Text (`VoiceCapability.STT`) — 5 providers

| id | displayName | requiredSettingKey | defaultModels() |
|---|---|---|---|
| `deepgram` | Deepgram | `DEEPGRAM_API_KEY` | `nova-3`, `flux`, `nova-3-medical` |
| `assemblyai` | AssemblyAI | `ASSEMBLYAI_API_KEY` | `universal-3-pro`, `universal-2` |
| `openai_stt` | OpenAI STT | `OPENAI_API_KEY` | `gpt-realtime-whisper`, `gpt-4o-transcribe`, `whisper-1` |
| `whisper` | Whisper (self-hosted) | `WHISPER_ENDPOINT` | `whisper-large-v3-turbo`, `whisper-large-v3` |
| `google_stt` | Google Cloud Speech-to-Text | `GOOGLE_CLOUD_PROJECT` | `chirp_3` — known but unavailable until the dedicated Speech-to-Text V2 backend and authentication are configured |

The `speech_to_text` **agent tool** (`SpeechToTextTool`, distinct from the gateway's streaming STT)
only talks to OpenAI Whisper directly — it checks the `openai` key and calls
`https://api.openai.com/v1/audio/transcriptions` with model `whisper-1` (default,
overridable via `SpeechToTextArgs.model`). It does not go through `VoiceProviderRegistry` at all.

Gemini audio understanding is not advertised as real-time STT. Gemini Live remains the Google S2S
path; the pipeline STT path targets the dedicated Cloud Speech-to-Text V2 `chirp_3` model.

**Subtotal (VoiceProviderRegistry, TTS+STT only): 9 providers.**

**Grand total documented here: 19 + 9 = 28 providers.**
(`VoiceProviderRegistry` additionally lists 3 `S2S` and 2 `TRANSLATE` providers — 5 more — for the
real-time voice relay; those aren't exposed as discrete agent tools and are out of scope for this
doc.)

---

## Routing rules

### CapabilityRouter-managed capabilities (image, vision, embeddings, video gen, video analysis)

1. A provider is "configured" iff **all** of its `credentialKeys` resolve to non-blank values in
   the `apiKeys` map passed into `ProviderRegistry`.
2. `CapabilityRouter.resolve(capability)` always returns one of `NotConfigured` / `Ready` /
   `MultipleAvailable` — there is no silent failure.
3. **Default selection when multiple providers are configured**: agent tools (`ImageGenerationTool`,
   `VisionTool`, `EmbeddingTool`, `VideoGenerateTool`, `VideoAnalyzeTool`) all pattern-match on the
   `CapabilityResolution` and, for `MultipleAvailable`, silently take `.providers.first()` — i.e.
   **the lowest-priority (= highest-preference) configured provider wins**, without asking the user.
   The `MultipleAvailable.choiceMessage` (numbered list) exists in the API but is not currently
   surfaced as an interactive prompt by these tools.
4. **No fallback across providers on failure.** If the selected provider's HTTP call throws or
   returns an error, the tool returns an `[ERROR] ...` string — it does not retry with the next
   configured provider in the list.
5. Priority order is fixed at compile time in `ProviderRegistry.allProviders` (e.g. OpenAI is
   priority 0 for image generation and vision; Google is priority 0 for video generation and video
   analysis) and is not configurable by the user.

### VoiceProviderRegistry-managed capabilities (TTS, STT via the gateway)

1. A provider is "available" iff its single `requiredSettingKey` resolves to a non-blank value
   from `database.getAllSettings()` merged with `CredentialsStore` (DB settings override
   credentials.json).
2. There is **no priority/auto-selection** step — `getProvidersByCapability()` just returns the
   filtered list. Provider + voice are chosen explicitly:
   - `TtsRoute` (`POST /api/v1/tts/synthesize`) defaults to `provider = "openai_tts"`,
     `voice = "alloy"` if the caller doesn't specify one.
   - `TextToSpeechTool` (the agent tool) defaults to the provider/voice stored in
     `CredentialsStore` (`voiceTtsProvider` / `voiceTtsVoice`), falling back to `"openai_tts"` /
     `"alloy"` if unset, and lets the LLM override both per call via `TextToSpeechArgs`.
3. `TtsSynthesizer.synthesize()` dispatches on the literal `providerId` string; a provider id with
   no matching `when` branch (e.g. `cartesia`) logs a warning and returns `null`, which the route
   surfaces as a 500.

---

## Related agent tools

| Tool name | Class | File | Capability / backend |
|---|---|---|---|
| `generate_image` | `ImageGenerationTool` | `shared/.../core/MediaTools.kt` | `Capability.IMAGE_GENERATION` via `CapabilityRouter` |
| `analyze_image` | `VisionTool` | `shared/.../core/MediaTools.kt` | `Capability.VISION` via `CapabilityRouter` |
| `text_to_speech` | `TextToSpeechTool` | `shared/.../core/MediaTools.kt` | Gateway `/api/v1/tts/synthesize` (`VoiceProviderRegistry`/`TtsSynthesizer`), not `CapabilityRouter` |
| `speech_to_text` | `SpeechToTextTool` | `shared/.../core/tools/ai/AiTools.kt` | OpenAI Whisper only (hardcoded `openai` key check) |
| `generate_video` | `VideoGenerateTool` | `shared/.../core/tools/media/VideoTools.kt` | `Capability.VIDEO_GENERATION` via `CapabilityRouter` |
| `analyze_video` | `VideoAnalyzeTool` | `shared/.../core/tools/media/VideoTools.kt` | `Capability.VIDEO_ANALYSIS` via `CapabilityRouter` |
| `embedding` | `EmbeddingTool` | `shared/.../core/tools/ai/AiTools.kt` | `Capability.EMBEDDINGS` via `CapabilityRouter` |

All seven are registered in
`promethe/shared/src/jvmMain/kotlin/dev/promethe/core/IntegrationRegistrar.kt`, which constructs
one shared `ProviderRegistry`/`CapabilityRouter` pair from the resolved `apiKeys` map and injects it
into each tool's constructor.

---

## Configuration

Credential keys are supplied at runtime (Setup Screen, Settings > AI Media/Integrations, or
`config_set`), never by hand-editing `.env`. The key names below are the exact strings checked by
`ProviderRegistry.credentialKeys` / `VoiceProvider.requiredSettingKey`.

| Credential key | Enables |
|---|---|
| `openai` | `openai-dalle3` (image), `openai-vision` (vision), `openai-embed` (embeddings), `openai-vision-frames` (video analysis, unimplemented) |
| `google` | `google-imagen3` (image), `google-gemini-vision` (vision), `google-embed` (embeddings), `google-veo3` (video gen, unimplemented), `google-gemini-video` (video analysis) |
| `anthropic` | `anthropic-claude-vision` (vision) |
| `stability` | `stability-ai` (image) |
| `replicate` | `replicate-flux` (image, unimplemented), `replicate-luma` (video gen) |
| `ollama_url` | `ollama-embed` (embeddings) |
| `cohere` | `cohere-embed` (embeddings) |
| `voyage` | `voyage-embed` (embeddings) |
| `mistral` | `mistral-embed` (embeddings) |
| `fal` | `fal-minimax` (video gen) |
| `runway` | `runway-gen3` (video gen) |
| `OPENAI_API_KEY` (gateway setting) | `openai_tts` (TTS), `openai_stt` (STT) — also reused by `speech_to_text`'s hardcoded Whisper call |
| `GOOGLE_API_KEY` (gateway setting) | `gemini_tts` (TTS) |
| `ELEVENLABS_API_KEY` | `elevenlabs` (TTS) |
| `CARTESIA_API_KEY` | `cartesia` (TTS, registered but not wired into `TtsSynthesizer`) |
| `DEEPGRAM_API_KEY` | `deepgram` (STT) |
| `ASSEMBLYAI_API_KEY` | `assemblyai` (STT) |
| `WHISPER_ENDPOINT` | `whisper` (STT, self-hosted) |

---

## Limitations

- **Video tools are registered twice.** `VideoGenerateTool` and `VideoAnalyzeTool` are instantiated
  and registered both in `IntegrationRegistrar.registerIntegrationTools()` and again in
  `IntegrationRegistrar.registerExtendedTools()` (`promethe/shared/src/jvmMain/kotlin/dev/promethe/core/IntegrationRegistrar.kt`).
  A fix is planned; this doc only notes the duplication.
- **No automatic fallback on provider failure.** If the selected provider's API call errors out,
  the tool returns an `[ERROR]` string rather than retrying with the next configured provider.
- **Several catalogued providers are not actually implemented** — they exist as `ProviderEntry` /
  `VoiceProvider` entries so they show up in "not configured" help messages, but their execution
  path is a stub: `replicate-flux` (image), `google-veo3` (video generation), `openai-vision-frames`
  (video analysis), and `cartesia` (TTS, no branch in `TtsSynthesizer`).
- **`MultipleAvailable` is not interactive in practice.** The router can produce a numbered-choice
  message for the user, but every tool that calls `CapabilityRouter.resolve()` immediately takes
  `.providers.first()` on that branch instead of surfacing the choice.
- **Two disconnected provider systems.** Image/vision/embeddings/video use
  `ProviderRegistry`/`CapabilityRouter` (priority-based auto-selection); TTS/STT use the gateway's
  `VoiceProviderRegistry` (explicit id-based selection, no priority ordering). They share credential
  keys in some cases (e.g. `openai` vs `OPENAI_API_KEY`) but are otherwise independent — there is no
  shared abstraction between them.
- **`speech_to_text` (agent tool) only supports OpenAI Whisper**, unlike the gateway's
  `VoiceProviderRegistry` STT list (5 providers); it does not consult that registry.
