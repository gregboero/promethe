package dev.promethe.core

import ai.koog.prompt.llm.LLMCapability

/**
 * ModelCapabilityRegistry — smart mapping of provider+model → Koog LLMCapability list.
 *
 * ## Why this exists
 *
 * Koog's [LLModel] requires explicit capability declarations. Some capabilities
 * route through internal code paths that require the model to be in Koog's
 * hardcoded registry:
 *
 * - [LLMCapability.Schema.JSON.Standard]      → triggers JSON schema mode (registry check)
 * - [LLMCapability.OpenAIEndpoint.Responses]  → routes to /v1/responses (registry required)
 *
 * Using these with unknown models throws "Cannot determine proper LLM params".
 * This registry uses ONLY capabilities that work with any model ID.
 *
 * ## Matching strategy
 *
 * 1. Exact model ID match
 * 2. Most-specific substring match (linked map, specificity-first ordering)
 * 3. Provider-level defaults
 * 4. Safe global fallback: [Temperature + Tools]
 *
 * ## Extending
 *
 * Add entries to [MODEL_OVERRIDES] for new models. Substring patterns are matched
 * case-insensitively. The map is ordered: more specific patterns MUST appear before
 * more general ones (e.g., "o1-mini" before "o1").
 */
object ModelCapabilityRegistry {
    /**
     * Runtime-learned capabilities, populated by [learnNoTools] / [learnCapabilities].
     *
     * Key format: "provider/model" (e.g., "openai/gpt-4o") OR just "model".
     * These override the static registry and persist for the lifetime of the JVM process.
     * On next server restart, learning restarts — intentionally, since model APIs evolve.
     */
    private val dynamicCache = HashMap<String, List<LLMCapability>>()

    /** Fallback when no provider/model match is found. */
    private val SAFE_DEFAULTS: List<LLMCapability> = listOf(
        LLMCapability.Temperature,
        LLMCapability.Tools,
    )

    /**
     * Provider-level defaults — applied when no model-specific override matches.
     * Most modern cloud models support Temperature + Tools.
     */
    private val PROVIDER_DEFAULTS: Map<String, List<LLMCapability>> = mapOf(
        "openai" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "anthropic" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "google" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "ollama" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "openrouter" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "deepseek" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "nvidia" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "litellm" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "vllm" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "kimi" to listOf(LLMCapability.Tools, LLMCapability.Thinking),
        "xai" to listOf(LLMCapability.Tools, LLMCapability.Thinking),
    )

    /**
     * Model-specific capability overrides.
     *
     * Pattern matching: [model].contains(pattern, ignoreCase = true)
     * Ordered most-specific first — DO NOT reorder without checking for conflicts.
     *
     * Legend:
     * - Temperature  → supports temperature param
     * - Tools        → supports function/tool calling
     *
     * Intentionally excluded (require Koog internal registry):
     * - Schema.JSON.Standard
     * - OpenAIEndpoint.Responses
     */
    private val MODEL_OVERRIDES: LinkedHashMap<String, List<LLMCapability>> = linkedMapOf(
        // Current certified frontier models. Sampling is intentionally omitted
        // when the provider fixes it or controls it through reasoning settings.
        "gpt-5.6" to listOf(LLMCapability.Tools, LLMCapability.Thinking),
        "claude-sonnet-5" to listOf(LLMCapability.Tools, LLMCapability.Thinking),
        "claude-opus-4-8" to listOf(LLMCapability.Tools, LLMCapability.Thinking),
        "gemini-3.5-flash" to listOf(LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.Thinking),
        "gemini-3.1-pro-preview" to listOf(LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.Thinking),
        "gemini-3.1-flash-lite" to listOf(LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.Thinking),
        "deepseek-v4" to listOf(LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.Thinking),
        "kimi-k3" to listOf(LLMCapability.Tools, LLMCapability.Thinking),
        "grok-4.5" to listOf(LLMCapability.Tools, LLMCapability.Thinking),
        // ── OpenAI: reasoning / o-series ─────────────────────────────────────
        // Legacy reasoning models: no tools, no user-facing temperature
        "o1-mini" to listOf(LLMCapability.Temperature),
        "o1-preview" to listOf(LLMCapability.Temperature),
        // Newer reasoning: tools supported, but temperature is fixed internally
        "o1" to listOf(LLMCapability.Tools),
        "o3-mini" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "o3" to listOf(LLMCapability.Tools),
        "o4-mini" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // ── OpenAI: GPT family ────────────────────────────────────────────────
        "gpt-4o" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "gpt-4.1" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "gpt-4" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "gpt-3.5" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // ── Anthropic: Claude family ─────────────────────────────────────────
        // All modern Claude models support tools + temperature
        "claude" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // ── Google: Gemini family ─────────────────────────────────────────────
        "gemini" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // ── DeepSeek ─────────────────────────────────────────────────────────
        // deepseek-reasoner (R1): tool calling experimental/unreliable
        "deepseek-reasoner" to listOf(LLMCapability.Temperature),
        "deepseek-r1" to listOf(LLMCapability.Temperature),
        // deepseek-chat: full support
        "deepseek" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // ── NVIDIA NIM ────────────────────────────────────────────────────────
        "llama-3.1" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "llama-3" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "nemotron" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "mistralai" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // ── Ollama: local models ──────────────────────────────────────────────
        // Old llama2 base: no tool support
        "llama2" to listOf(LLMCapability.Temperature),
        // Modern llama3+ supports tools
        "llama3" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "llama3.1" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "llama3.2" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // Mistral / Mixtral
        "mixtral" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "mistral" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // Gemma: no reliable tool support
        "gemma" to listOf(LLMCapability.Temperature),
        // Phi: no tool support in most versions
        "phi" to listOf(LLMCapability.Temperature),
        // Qwen: modern versions support tools
        "qwen2.5" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        "qwen" to listOf(LLMCapability.Temperature),
        // Command-R
        "command-r" to listOf(LLMCapability.Temperature, LLMCapability.Tools),
        // CodeLlama
        "codellama" to listOf(LLMCapability.Temperature),
    )

    /**
     * Returns the appropriate [LLMCapability] list for the given provider and model.
     *
     * Resolution order:
     * 1. **Runtime-learned** (from [learnNoTools] / [learnCapabilities]) — highest priority
     * 2. **Exact model ID** match in static registry
     * 3. **Substring match** in static registry (ordered most-specific first)
     * 4. **Provider default**
     * 5. **Safe global fallback** `[Temperature]`
     *
     * @param provider Provider key (e.g., "openai", "anthropic", "ollama")
     * @param model    Model ID as passed to the LLM API (e.g., "gpt-4o", "claude-sonnet-4-5")
     */
    fun capabilitiesFor(
        provider: String,
        model: String,
    ): List<LLMCapability> {
        // 1. Runtime-learned (highest priority)
        val learned = dynamicCache["$provider/$model"] ?: dynamicCache[model]
        if (learned != null) return learned

        // 2. Exact match
        MODEL_OVERRIDES[model]?.let { return it }

        // 3. Substring match (ordered most-specific → least-specific)
        for ((pattern, caps) in MODEL_OVERRIDES) {
            if (model.contains(pattern, ignoreCase = true)) return caps
        }

        // 4. Provider default
        PROVIDER_DEFAULTS[provider.lowercase()]?.let { return it }

        // 5. Safe global fallback
        return SAFE_DEFAULTS
    }

    /**
     * Record that a model does NOT support function/tool calling.
     *
     * Called automatically by [KoogLlmAdapter] when an API call fails with a
     * tool-related error. The learned capability is cached for the JVM lifetime
     * and used on all subsequent calls — no more retries needed.
     *
     * @param provider Provider key
     * @param model    Exact model ID that failed
     */
    fun learnNoTools(
        provider: String,
        model: String,
    ) {
        val downgraded = capabilitiesFor(provider, model).filter { it != LLMCapability.Tools }
        dynamicCache["$provider/$model"] = downgraded
        dynamicCache[model] = downgraded
    }

    /**
     * Record an explicit capability list for a provider+model at runtime.
     *
     * Useful when the caller has external capability information (e.g., from
     * an OpenRouter model metadata response).
     */
    fun learnCapabilities(
        provider: String,
        model: String,
        capabilities: List<LLMCapability>,
    ) {
        dynamicCache["$provider/$model"] = capabilities
        dynamicCache[model] = capabilities
    }

    /** Returns a snapshot of all runtime-learned overrides (for debug/status endpoints). */
    fun learnedOverrides(): Map<String, List<String>> = dynamicCache.mapValues { (_, caps) -> caps.map { it.toString() } }

    /**
     * Returns true if the given model supports function/tool calling.
     * Useful for disabling tool-related UI when the model can't use them.
     */
    fun supportsTools(
        provider: String,
        model: String,
    ): Boolean = LLMCapability.Tools in capabilitiesFor(provider, model)

    /**
     * Returns true if the given model accepts a temperature parameter.
     */
    fun supportsTemperature(
        provider: String,
        model: String,
    ): Boolean = LLMCapability.Temperature in capabilitiesFor(provider, model)
}
