package net.aechronis.discord

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

internal data class CommandReply(
    val lines: List<String>,
) {
    constructor(message: String) : this(listOf(message))

    fun pages(): List<String> =
        buildList {
            val page = StringBuilder()
            lines.joinToString("\n").codePoints().forEach { codePoint ->
                val character = String(Character.toChars(codePoint))
                val escaped = if (character in markdownCharacters) "\\$character" else character
                if (page.length + escaped.length > 4096) {
                    add(page.toString())
                    page.clear()
                }
                page.append(escaped)
            }
            if (page.isNotEmpty()) add(page.toString())
        }

    companion object {
        private val markdownCharacters = "\\`*_{}[]()#+-.!|>~".map(Char::toString).toSet()

        fun plain(message: Component): String {
            val plain = PlainTextComponentSerializer.plainText()
            return plain.serialize(LegacyComponentSerializer.legacySection().deserialize(plain.serialize(message)))
        }
    }
}
