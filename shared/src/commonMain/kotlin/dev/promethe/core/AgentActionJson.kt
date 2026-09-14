package dev.promethe.core

/** Extract one object; the caller still validates JSON, tool arguments and policy. */
internal object AgentActionJson {
    private val fence = Regex("""```(?:json)?\s*\n?(.*?)\n?\s*```""", RegexOption.DOT_MATCHES_ALL)

    fun extract(text: String): String? {
        val candidate = if (text.trimStart().startsWith("{")) text else fence.find(text)?.groupValues?.get(1) ?: text
        val start = candidate.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until candidate.length) {
            val character = candidate[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> {
                        inString = true
                    }

                    '{' -> {
                        depth++
                    }

                    '}' -> {
                        depth--
                        if (depth == 0) return candidate.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }
}
