package net.aechronis.logger.params

enum class ParamKey(
    val aliases: List<String>,
    val desc: String,
) {
    USER(listOf("u", "user"), "user(s)"),
    TIME(listOf("t", "time"), "time window"),
    RADIUS(listOf("r", "radius"), "block radius"),
    CHUNK_RADIUS(listOf("cr", "chunkradius"), "chunk radius"),
    ACTION(listOf("a", "actions"), "action(s)"),
    INCLUDE(listOf("i", "include"), "include block(s)"),
    EXCLUDE(listOf("e", "exclude"), "exclude block(s)"),
    ;

    companion object {
        fun fromKey(key: String): ParamKey? = entries.firstOrNull { e -> e.aliases.any { it.equals(key, ignoreCase = true) } }
    }
}
