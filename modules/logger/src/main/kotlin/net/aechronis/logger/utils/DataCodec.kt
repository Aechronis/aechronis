package net.aechronis.logger.utils

object DataCodec {
    fun encode(data: Map<String, String>): String = data.entries.joinToString(";") { (k, v) -> "${escape(k)}=${escape(v)}" }

    fun decode(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()

        val out = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inValue = false
        var i = 0

        fun flush() {
            if (key.isNotEmpty() || value.isNotEmpty()) out[key.toString()] = value.toString()
            key.clear()
            value.clear()
            inValue = false
        }

        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val next = raw[i + 1]
                    val decoded =
                        when (next) {
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> next
                        }
                    if (inValue) value.append(decoded) else key.append(decoded)
                    i += 2
                }

                c == '=' && !inValue -> {
                    inValue = true
                    i++
                }

                c == ';' -> {
                    flush()
                    i++
                }

                else -> {
                    if (inValue) value.append(c) else key.append(c)
                    i++
                }
            }
        }
        flush()

        return out
    }

    private fun escape(s: String): String =
        buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    ';' -> append("\\;")
                    '=' -> append("\\=")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(c)
                }
            }
        }
}
