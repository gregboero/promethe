package dev.promethe.core.hooks

/**
 * Predefined sets of guardrail patterns for different security levels.
 *
 * - [DEV_SAFE] (default): blocks destructive system commands and common attack vectors.
 * - [PRODUCTION_STRICT]: DEV_SAFE + blocks dangerous tool categories and outbound network exploits.
 *
 * Selected via `GUARDRAIL_PRESET` config key (default: "dev-safe").
 */
object GuardrailPresets {
    data class Preset(
        val name: String,
        val blockedTools: Set<String> = emptySet(),
        val blockedArgPatterns: List<Regex>,
    )

    /** Common dangerous command patterns — blocks disk wipes, fork bombs, pipe-to-shell, etc. */
    private val DEV_SAFE_PATTERNS = listOf(
        // Disk wipe / recursive delete
        Regex("""rm\s+-rf\s+/"""),
        Regex("""rm\s+-rf\s+/\*"""),
        // Fork bomb (canonical: :(){ :|:& };:)
        Regex(""":\(\)\s*\{.*\|.*&.*\}\s*;"""),
        // Filesystem destruction
        Regex("""mkfs\."""),
        Regex("""dd\s+if=/dev/(zero|random|urandom)"""),
        // Windows destructive commands
        Regex("""format\s+[c-zC-Z]:"""),
        Regex("""del\s+/[sS]\s+/[qQ]"""),
        Regex("""rd\s+/[sS]\s+/[qQ]"""),
        // System shutdown/reboot
        Regex("""(?:^|\s|")(shutdown|reboot)\s"""),
        // Pipe-to-shell (curl/wget piped to bash)
        Regex("""curl\s+.*\|\s*(ba)?sh"""),
        Regex("""wget\s+.*\|\s*(ba)?sh"""),
        // Dangerous permission changes
        Regex("""chmod\s+-R\s+777\s+/"""),
    )

    /** Additional patterns for production environments — outbound exploits, reverse shells. */
    private val PRODUCTION_EXTRA_PATTERNS = listOf(
        // Reverse shell via netcat
        Regex("""nc\s+-e"""),
        Regex("""ncat\s+-e"""),
        // Bash reverse shell via /dev/tcp
        Regex("""/dev/tcp/"""),
        // Python reverse shell
        Regex("""python.*socket.*connect"""),
    )

    val DEV_SAFE = Preset(
        name = "dev-safe",
        blockedArgPatterns = DEV_SAFE_PATTERNS,
    )

    val PRODUCTION_STRICT = Preset(
        name = "production-strict",
        blockedTools = setOf("execute_code", "send_email"),
        blockedArgPatterns = DEV_SAFE_PATTERNS + PRODUCTION_EXTRA_PATTERNS,
    )

    /**
     * Resolve a preset by name.
     * Falls back to [DEV_SAFE] for unknown names.
     */
    fun resolve(name: String): Preset =
        when (name.lowercase()) {
            "dev-safe" -> {
                DEV_SAFE
            }

            "production-strict" -> {
                PRODUCTION_STRICT
            }

            else -> {
                val logger = dev.promethe.core.Log.create("GuardrailPresets")
                logger.warn { "Unknown guardrail preset '$name', falling back to 'dev-safe'" }
                DEV_SAFE
            }
        }
}
