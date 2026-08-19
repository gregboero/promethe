package dev.promethe.core

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Owner-visible request created while a remote MCP tool is waiting for form input. */
@Serializable
data class PendingMcpElicitation(
    val id: String,
    val serverId: String,
    val serverName: String,
    val requestKey: String,
    val sessionId: String,
    val message: String,
    val requestedSchema: JsonObject,
    val createdAt: Long,
    val expiresAt: Long,
)

/**
 * Bridges modern MCP `elicitation/create` input requests to the authenticated owner UI.
 * Requests stay in memory because the originating tool coroutine cannot survive a process restart.
 */
class McpElicitationBroker(
    private val timeoutMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nextId: () -> String = ::secureElicitationId,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
) {
    init {
        require(timeoutMs > 0) { "MCP elicitation timeout must be positive" }
        require(maxPending > 0) { "MCP elicitation pending limit must be positive" }
    }

    enum class ResponseResult {
        ACCEPTED,
        NOT_FOUND,
        INVALID_ACTION,
        INVALID_CONTENT,
    }

    private data class PendingRequest(
        val public: PendingMcpElicitation,
        val completion: CompletableDeferred<JsonObject> = CompletableDeferred(),
        val resolved: AtomicBoolean = AtomicBoolean(false),
    )

    private val pending = ConcurrentHashMap<String, PendingRequest>()

    fun handlerFor(
        serverId: String,
        serverName: String,
    ): McpInputRequestHandler =
        McpInputRequestHandler { requestKey, request ->
            fulfill(serverId, serverName, requestKey, request)
        }

    fun listPending(): List<PendingMcpElicitation> =
        pending.values
            .asSequence()
            .filterNot { it.completion.isCompleted }
            .filter { it.public.expiresAt > clock() }
            .map(PendingRequest::public)
            .sortedByDescending(PendingMcpElicitation::createdAt)
            .toList()

    fun respond(
        requestId: String,
        action: String,
        content: JsonObject?,
    ): ResponseResult {
        val request = pending[requestId] ?: return ResponseResult.NOT_FOUND
        val normalizedAction = action.lowercase()
        if (normalizedAction !in VALID_ACTIONS) return ResponseResult.INVALID_ACTION
        if (normalizedAction == ACTION_ACCEPT && !validContent(request.public.requestedSchema, content)) {
            return ResponseResult.INVALID_CONTENT
        }
        val response =
            buildJsonObject {
                put("action", normalizedAction)
                if (normalizedAction == ACTION_ACCEPT) put("content", requireNotNull(content))
            }
        if (!request.resolved.compareAndSet(false, true)) return ResponseResult.NOT_FOUND
        val expired = clock() >= request.public.expiresAt
        val completed = request.completion.complete(if (expired) cancelledResponse() else response)
        pending.remove(requestId, request)
        return if (completed && !expired) ResponseResult.ACCEPTED else ResponseResult.NOT_FOUND
    }

    private suspend fun fulfill(
        serverId: String,
        serverName: String,
        requestKey: String,
        request: JsonObject,
    ): JsonObject {
        require(request["method"]?.jsonPrimitive?.contentOrNull == ELICITATION_METHOD) {
            "Unsupported MCP input request method"
        }
        val params = request["params"] as? JsonObject
            ?: throw IllegalArgumentException("MCP elicitation is missing params")
        val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "form"
        require(mode == "form") { "Only MCP form elicitation is supported" }
        val message =
            params["message"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_MESSAGE_LENGTH }
                ?: throw IllegalArgumentException("MCP elicitation has an invalid message")
        val schema = params["requestedSchema"] as? JsonObject
            ?: throw IllegalArgumentException("MCP elicitation is missing requestedSchema")
        validateSchema(schema)
        val invocation = currentToolInvocation()
            ?: throw IllegalStateException("MCP elicitation requires a secured tool invocation")
        val now = clock()
        val public =
            PendingMcpElicitation(
                id = nextId(),
                serverId = serverId,
                serverName = serverName,
                requestKey = requestKey,
                sessionId = invocation.sessionId,
                message = message,
                requestedSchema = schema,
                createdAt = now,
                expiresAt = now + timeoutMs,
            )
        val queued = PendingRequest(public)
        synchronized(pending) {
            check(pending.size < maxPending) { "Too many pending MCP elicitation requests" }
            check(pending.putIfAbsent(public.id, queued) == null) { "Duplicate MCP elicitation request id" }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { queued.completion.await() }
                ?: if (queued.resolved.compareAndSet(false, true)) {
                    cancelledResponse().also(queued.completion::complete)
                } else {
                    queued.completion.await()
                }
        } finally {
            pending.remove(public.id, queued)
        }
    }

    private fun validateSchema(schema: JsonObject) {
        require(schema.toString().toByteArray(Charsets.UTF_8).size <= MAX_SCHEMA_BYTES) {
            "MCP elicitation schema is too large"
        }
        require(schema.keys.all(ALLOWED_SCHEMA_KEYS::contains)) {
            "MCP elicitation schema contains unsupported keywords"
        }
        require(schema["type"]?.jsonPrimitive?.contentOrNull == "object") {
            "MCP elicitation schema must describe an object"
        }
        val properties = schema["properties"] as? JsonObject
            ?: throw IllegalArgumentException("MCP elicitation schema is missing properties")
        require(properties.isNotEmpty() && properties.size <= MAX_PROPERTIES) {
            "MCP elicitation schema must contain 1 to $MAX_PROPERTIES properties"
        }
        val required = requireNotNull(schema.requiredNames()) {
            "MCP elicitation required fields must be a string array"
        }
        require(required.all(properties::containsKey)) { "MCP elicitation required fields must exist" }
        properties.forEach { (name, definitionValue) ->
            require(name.isNotBlank() && name.length <= MAX_FIELD_NAME_LENGTH) {
                "MCP elicitation contains an invalid field name"
            }
            val definition = definitionValue as? JsonObject
                ?: throw IllegalArgumentException("MCP elicitation field '$name' must be an object")
            require(definition.keys.all(ALLOWED_PROPERTY_KEYS::contains)) {
                "MCP elicitation field '$name' contains unsupported keywords"
            }
            val type = (definition["type"] as? JsonPrimitive)?.contentOrNull
            require(type in SUPPORTED_TYPES) { "Unsupported MCP elicitation field type for '$name'" }
            require(validOptionalText(definition["title"], MAX_TITLE_LENGTH)) {
                "MCP elicitation title for '$name' is invalid"
            }
            require(validOptionalText(definition["description"], MAX_DESCRIPTION_LENGTH)) {
                "MCP elicitation description for '$name' is invalid"
            }
            require(!isSensitiveField(name, definition)) {
                "MCP elicitation cannot request sensitive information"
            }
            require(validDeclaredBounds(type, definition)) {
                "MCP elicitation bounds for '$name' are invalid"
            }
            val enum = definition["enum"] as? JsonArray
            require(enum == null || (enum.isNotEmpty() && enum.size <= MAX_ENUM_VALUES)) {
                "MCP elicitation enum for '$name' is invalid"
            }
            require(enum?.all { validValue(definition, it) } != false) {
                "MCP elicitation enum for '$name' contains invalid values"
            }
            require(definition["default"]?.let { validValue(definition, it) } != false) {
                "MCP elicitation default for '$name' is invalid"
            }
        }
    }

    private fun validOptionalText(
        value: JsonElement?,
        maxLength: Int,
    ): Boolean =
        value == null ||
            (value is JsonPrimitive && value.isString && value.content.length <= maxLength)

    private fun validContent(
        schema: JsonObject,
        content: JsonObject?,
    ): Boolean {
        if (content == null) return false
        val properties = schema["properties"] as? JsonObject ?: return false
        if (content.keys.any { it !in properties }) return false
        val required = schema.requiredNames() ?: return false
        if (!required.all(content::containsKey)) return false
        return content.all { (name, value) ->
            val definition = properties[name] as? JsonObject ?: return@all false
            validValue(definition, value)
        }
    }

    private fun validValue(
        definition: JsonObject,
        value: JsonElement,
    ): Boolean {
        if (value is JsonNull) return false
        val primitive = value as? JsonPrimitive ?: return false
        val type = definition["type"]?.jsonPrimitive?.contentOrNull ?: return false
        val typeMatches =
            when (type) {
                "string" -> {
                    primitive.isString && validStringBounds(definition, primitive.content)
                }

                "number" -> {
                    !primitive.isString &&
                        primitive.doubleOrNull?.let { validNumberBounds(definition, it) } == true
                }

                "integer" -> {
                    !primitive.isString &&
                        primitive.longOrNull?.let { validNumberBounds(definition, it.toDouble()) } == true
                }

                "boolean" -> {
                    !primitive.isString && primitive.content in setOf("true", "false")
                }

                else -> {
                    false
                }
            }
        if (!typeMatches) return false
        val enum = definition["enum"] as? JsonArray
        return enum == null || value in enum
    }

    private fun validStringBounds(
        definition: JsonObject,
        value: String,
    ): Boolean {
        val min = definition["minLength"]?.jsonPrimitive?.longOrNull ?: 0L
        val max = definition["maxLength"]?.jsonPrimitive?.longOrNull ?: MAX_STRING_LENGTH.toLong()
        return min in 0..MAX_STRING_LENGTH && max in min..MAX_STRING_LENGTH && value.length.toLong() in min..max
    }

    private fun validNumberBounds(
        definition: JsonObject,
        value: Double,
    ): Boolean {
        val minimum = (definition["minimum"] as? JsonPrimitive)?.doubleOrNull
        val maximum = (definition["maximum"] as? JsonPrimitive)?.doubleOrNull
        return (minimum == null || value >= minimum) && (maximum == null || value <= maximum)
    }

    private fun validDeclaredBounds(
        type: String?,
        definition: JsonObject,
    ): Boolean =
        when (type) {
            "string" -> {
                val minElement = definition["minLength"]
                val maxElement = definition["maxLength"]
                val min = (minElement as? JsonPrimitive)?.longOrNull ?: 0L
                val max = (maxElement as? JsonPrimitive)?.longOrNull ?: MAX_STRING_LENGTH.toLong()
                (minElement == null || (minElement is JsonPrimitive && minElement.longOrNull != null)) &&
                    (maxElement == null || (maxElement is JsonPrimitive && maxElement.longOrNull != null)) &&
                    min in 0..MAX_STRING_LENGTH &&
                    max in min..MAX_STRING_LENGTH
            }

            "number", "integer" -> {
                val minimumElement = definition["minimum"]
                val maximumElement = definition["maximum"]
                val minimum = (minimumElement as? JsonPrimitive)?.doubleOrNull
                val maximum = (maximumElement as? JsonPrimitive)?.doubleOrNull
                (minimumElement == null || minimum != null) &&
                    (maximumElement == null || maximum != null) &&
                    (minimum == null || maximum == null || minimum <= maximum)
            }

            else -> {
                true
            }
        }

    private fun JsonObject.requiredNames(): Set<String>? {
        val element = get("required") ?: return emptySet()
        val array = element as? JsonArray ?: return null
        val names =
            array.map {
                (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull ?: return null
            }
        return names.toSet().takeIf { it.size == names.size }
    }

    private fun isSensitiveField(
        name: String,
        definition: JsonObject,
    ): Boolean {
        val raw =
            buildString {
                append(name)
                append(' ')
                append(definition["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                append(' ')
                append(definition["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        val normalized =
            raw
                .replace(CAMEL_CASE_BOUNDARY, "\$1 \$2")
                .lowercase()
                .replace(NON_ALPHANUMERIC, " ")
                .trim()
                .replace(REPEATED_WHITESPACE, " ")
        val words = normalized.split(' ').filter(String::isNotBlank).toSet()
        val compact = normalized.replace(" ", "")
        return SENSITIVE_PHRASES.any(normalized::contains) ||
            SENSITIVE_SHORT_WORDS.any(words::contains) ||
            SENSITIVE_COMPACT_TERMS.any(compact::contains)
    }

    companion object {
        private const val ELICITATION_METHOD = "elicitation/create"
        private const val ACTION_ACCEPT = "accept"
        private const val ACTION_CANCEL = "cancel"
        private const val DEFAULT_MAX_PENDING = 32
        private const val MAX_MESSAGE_LENGTH = 2_000
        private const val MAX_FIELD_NAME_LENGTH = 128
        private const val MAX_PROPERTIES = 16
        private const val MAX_ENUM_VALUES = 32
        private const val MAX_STRING_LENGTH = 8_192
        private const val MAX_TITLE_LENGTH = 256
        private const val MAX_DESCRIPTION_LENGTH = 1_000
        private const val MAX_SCHEMA_BYTES = 64 * 1_024
        private val VALID_ACTIONS = setOf(ACTION_ACCEPT, "decline", ACTION_CANCEL)
        private val SUPPORTED_TYPES = setOf("string", "number", "integer", "boolean")
        private val ALLOWED_SCHEMA_KEYS = setOf("type", "properties", "required")
        private val ALLOWED_PROPERTY_KEYS =
            setOf(
                "type",
                "title",
                "description",
                "enum",
                "default",
                "minLength",
                "maxLength",
                "minimum",
                "maximum",
            )
        private val CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val REPEATED_WHITESPACE = Regex("\\s+")
        private val SENSITIVE_PHRASES =
            setOf(
                "password",
                "passphrase",
                "secret",
                "token",
                "api key",
                "api_key",
                "access key",
                "access_key",
                "credential",
                "authorization",
                "bearer",
                "private key",
                "private_key",
                "ssh key",
                "credit card",
                "card number",
                "cvv",
                "social security",
                "verification code",
                "one time password",
                "recovery code",
                "security code",
                "seed phrase",
                "bank account",
                "routing number",
            )
        private val SENSITIVE_SHORT_WORDS = setOf("otp", "mfa", "2fa", "pin", "cvv")
        private val SENSITIVE_COMPACT_TERMS =
            setOf(
                "apikey",
                "accesskey",
                "privatekey",
                "sshkey",
                "creditcard",
                "cardnumber",
                "socialsecurity",
                "verificationcode",
                "onetimepassword",
                "recoverycode",
                "securitycode",
                "seedphrase",
                "bankaccount",
                "routingnumber",
            )
    }
}

private fun cancelledResponse(): JsonObject = buildJsonObject { put("action", "cancel") }

private fun secureElicitationId(): String {
    val random = ByteArray(18).also { SecureRandom().nextBytes(it) }
    return "mcp-input-${Base64.getUrlEncoder().withoutPadding().encodeToString(random)}"
}
