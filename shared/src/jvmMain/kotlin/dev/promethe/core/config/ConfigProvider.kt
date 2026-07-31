package dev.promethe.core.config

/**
 * Centralized configuration provider.
 *
 * Resolves configuration values with the following priority:
 *   1. Process-level overrides (explicit CLI flags, e.g. --allow-local-exec)
 *   2. UI-persisted config (credentials.json via CredentialsStore)
 *   3. System environment variables (System.getenv)
 *   4. Default value (if provided)
 *
 * This replaces all direct System.getenv() calls to ensure the Settings UI
 * takes precedence over raw environment variables.
 */
interface ConfigProvider {
    /** Get a config value by key, or null if not set anywhere. */
    fun get(key: String): String?

    /** Get a config value by key with a default fallback. */
    fun get(
        key: String,
        default: String,
    ): String

    /** Get a boolean config value (true if the value equals "true" ignoring case). */
    fun getBoolean(
        key: String,
        default: Boolean = false,
    ): Boolean

    /** Get an integer config value. */
    fun getInt(
        key: String,
        default: Int,
    ): Int

    /** Get a long config value. */
    fun getLong(
        key: String,
        default: Long,
    ): Long

    /** Get a float config value. */
    fun getFloat(
        key: String,
        default: Float,
    ): Float

    /** Get all config keys (merged from all sources). */
    fun keys(): Set<String>

    companion object {
        /**
         * Global singleton. Initialized once at bootstrap.
         * Defaults to a SystemEnv-only provider until overridden.
         */
        @Volatile
        private var instance: ConfigProvider = SystemEnvConfigProvider

        /**
         * Process-level overrides set by explicit CLI flags (highest priority).
         * Survives provider re-initialization: launchDesktop/launchDaemon may
         * initialize the merged provider after flags are parsed.
         */
        private val overrides = java.util.concurrent.ConcurrentHashMap<String, String>()

        private val overrideAware =
            object : ConfigProvider {
                override fun get(key: String): String? = overrides[key] ?: instance.get(key)

                override fun get(
                    key: String,
                    default: String,
                ): String = overrides[key] ?: instance.get(key, default)

                override fun getBoolean(
                    key: String,
                    default: Boolean,
                ): Boolean = overrides[key]?.lowercase()?.let { it == "true" } ?: instance.getBoolean(key, default)

                override fun getInt(
                    key: String,
                    default: Int,
                ): Int = overrides[key]?.toIntOrNull() ?: instance.getInt(key, default)

                override fun getLong(
                    key: String,
                    default: Long,
                ): Long = overrides[key]?.toLongOrNull() ?: instance.getLong(key, default)

                override fun getFloat(
                    key: String,
                    default: Float,
                ): Float = overrides[key]?.toFloatOrNull() ?: instance.getFloat(key, default)

                override fun keys(): Set<String> = overrides.keys + instance.keys()
            }

        fun get(): ConfigProvider = overrideAware

        fun initialize(provider: ConfigProvider) {
            instance = provider
        }

        /** Set a process-level override (explicit CLI flag). Beats UI config and env vars. */
        fun setOverride(
            key: String,
            value: String,
        ) {
            overrides[key] = value
        }

        /** Remove a process-level override (used by tests). */
        fun clearOverride(key: String) {
            overrides.remove(key)
        }
    }
}

/**
 * Minimal fallback provider that reads only from System.getenv().
 * Used before the full provider is initialized (e.g. during early bootstrap).
 */
object SystemEnvConfigProvider : ConfigProvider {
    override fun get(key: String): String? = System.getenv(key)

    override fun get(
        key: String,
        default: String,
    ): String = System.getenv(key) ?: default

    override fun getBoolean(
        key: String,
        default: Boolean,
    ): Boolean = System.getenv(key)?.lowercase()?.let { it == "true" } ?: default

    override fun getInt(
        key: String,
        default: Int,
    ): Int = System.getenv(key)?.toIntOrNull() ?: default

    override fun getLong(
        key: String,
        default: Long,
    ): Long = System.getenv(key)?.toLongOrNull() ?: default

    override fun getFloat(
        key: String,
        default: Float,
    ): Float = System.getenv(key)?.toFloatOrNull() ?: default

    override fun keys(): Set<String> = System.getenv().keys
}

/**
 * Full config provider that merges UI-persisted credentials.json with System.getenv().
 *
 * Priority: credentials.json (UI) > System.getenv()
 *
 * @param envJsonLoader A function that loads all key-value pairs from CredentialsStore.toEnvMap().
 *                      This is injected from the gateway module to avoid a compile-time dependency
 *                      from shared → gateway.
 */
class MergedConfigProvider(
    private val envJsonLoader: () -> Map<String, String>,
) : ConfigProvider {
    override fun get(key: String): String? {
        // Priority: UI-persisted (credentials.json) > System.getenv
        return envJsonLoader()[key] ?: System.getenv(key)
    }

    override fun get(
        key: String,
        default: String,
    ): String = get(key) ?: default

    override fun getBoolean(
        key: String,
        default: Boolean,
    ): Boolean = get(key)?.lowercase()?.let { it == "true" } ?: default

    override fun getInt(
        key: String,
        default: Int,
    ): Int = get(key)?.toIntOrNull() ?: default

    override fun getLong(
        key: String,
        default: Long,
    ): Long = get(key)?.toLongOrNull() ?: default

    override fun getFloat(
        key: String,
        default: Float,
    ): Float = get(key)?.toFloatOrNull() ?: default

    override fun keys(): Set<String> = envJsonLoader().keys + System.getenv().keys
}
