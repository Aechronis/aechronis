package net.aechronis.watchdog.objects

import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag

internal object TranslationProbeCodec {
    const val FALLBACK = "faild"

    fun signData(key: String): CompoundBinaryTag {
        val lines =
            ListBinaryTag
                .builder(BinaryTagTypes.STRING)
                .add(StringBinaryTag.stringBinaryTag(componentJson(key)))
                .add(StringBinaryTag.stringBinaryTag(componentJson(FALLBACK, plain = true)))
                .add(StringBinaryTag.stringBinaryTag(componentJson(FALLBACK, plain = true)))
                .add(StringBinaryTag.stringBinaryTag(componentJson(FALLBACK, plain = true)))
                .build()
        val backText = text(lines)
        return CompoundBinaryTag
            .builder()
            .put("front_text", text(lines))
            .put("back_text", backText)
            .putString("color", "black")
            .putBoolean("is_waxed", false)
            .build()
    }

    fun isResolved(response: String): Boolean {
        val value = response.trim()
        return value != FALLBACK && value != "{\"text\":\"$FALLBACK\"}"
    }

    fun result(
        resolvedKeys: Collection<String>,
        forbiddenKeys: Set<String>,
    ): TranslationProbe =
        TranslationProbe(
            resolvedKeys = resolvedKeys.toList(),
            forbiddenKeys = resolvedKeys.filter(forbiddenKeys::contains),
        )

    private fun text(lines: ListBinaryTag): CompoundBinaryTag =
        CompoundBinaryTag
            .builder()
            .put("messages", lines)
            .put("filtered_messages", lines)
            .putString("color", "black")
            .putBoolean("has_glowing_text", false)
            .build()

    private fun componentJson(
        value: String,
        plain: Boolean = false,
    ): String {
        if (plain) return "{\"text\":\"${escape(value)}\"}"
        return "{\"translate\":\"${escape(value)}\",\"fallback\":\"$FALLBACK\"}"
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
