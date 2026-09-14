import kotlinx.serialization.json.*

val raw = observation.text
val result = try {
    val obj = Json.parseToJsonElement(raw) as? JsonObject
    obj?.get("answer")?.let { element ->
        when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    } ?: raw
} catch (_: Exception) {
    Regex("(?:^|[\\s,])answer=([^\\s,]+)").find(raw)?.groupValues?.get(1) ?: run {
        val lines = raw.lines()
        if (lines.size >= 2 && lines[0].split(',').contains("answer")) {
            val index = lines[0].split(',').indexOf("answer")
            lines[1].split(',').getOrNull(index) ?: raw
        } else raw
    }
}
result