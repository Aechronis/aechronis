package net.aechronis.logger.params

import net.aechronis.logger.objects.BlockAction

sealed interface ParseResult {
    data class Ok(
        val query: LookupQuery,
    ) : ParseResult

    data class Err(
        val message: String,
    ) : ParseResult
}

object ParamManager {
    private val tokenRegex = Regex("^([a-z]+):(.+)$")
    private val durationRegex = Regex("^(\\d+[wdhms])+$")
    private val durationUnit = Regex("(\\d+)([wdhms])")

    fun parse(tokens: Array<String>): ParseResult {
        var users: List<String> = emptyList()
        var since: Long? = null
        var radius: Int? = null
        var chunkRadius: Int? = null
        val rawActionTokens = mutableListOf<String>()
        var include: List<String> = emptyList()
        var exclude: List<String> = emptyList()

        for (token in tokens) {
            if (token.isBlank()) continue

            val match = tokenRegex.matchEntire(token) ?: return ParseResult.Err("Invalid parameter: '$token'")
            val keyStr = match.groupValues[1]
            val value = match.groupValues[2]
            val key = ParamKey.fromKey(keyStr) ?: return ParseResult.Err("Unknown parameter: '$keyStr:'")

            when (key) {
                ParamKey.USER -> {
                    users = value.split(',').filter { it.isNotBlank() }
                }

                ParamKey.TIME -> {
                    val millis = parseDuration(value) ?: return ParseResult.Err("Invalid time: '$value' (try 1w2d3h4m5s)")
                    since = System.currentTimeMillis() - millis
                }

                ParamKey.RADIUS -> {
                    val r = value.toIntOrNull()
                    if (r == null || r < 0) return ParseResult.Err("Invalid radius: '$value'")
                    radius = r
                }

                ParamKey.CHUNK_RADIUS -> {
                    val cr = value.toIntOrNull()
                    if (cr == null || cr < 1) return ParseResult.Err("Invalid chunk radius: '$value' (must be >= 1)")
                    chunkRadius = cr
                }

                ParamKey.ACTION -> {
                    rawActionTokens += value
                }

                ParamKey.INCLUDE -> {
                    include = value.split(',').filter { it.isNotBlank() }.map(::normalizeBlock)
                }

                ParamKey.EXCLUDE -> {
                    exclude = value.split(',').filter { it.isNotBlank() }.map(::normalizeBlock)
                }
            }
        }

        var actions: MutableSet<BlockAction>? = null
        for (rawToken in rawActionTokens) {
            val mapped = mapAction(rawToken) ?: return ParseResult.Err("Invalid action: '$rawToken'")
            actions = (actions ?: mutableSetOf()).apply { addAll(mapped) }
        }

        return ParseResult.Ok(LookupQuery.Block(LookupParams(users, since, radius, chunkRadius, actions, include, exclude)))
    }

    private fun mapAction(value: String): Set<BlockAction>? =
        when (value.lowercase()) {
            "break" -> setOf(BlockAction.BREAK)
            "place" -> setOf(BlockAction.PLACE)
            "interact", "use" -> setOf(BlockAction.INTERACT)
            "block" -> setOf(BlockAction.BREAK, BlockAction.PLACE)
            else -> null
        }

    private fun normalizeBlock(raw: String): String {
        val v = raw.lowercase()
        return if (v.contains(':')) v else "minecraft:$v"
    }

    private fun parseDuration(value: String): Long? {
        if (!durationRegex.matches(value)) return null
        var millis = 0L
        for (m in durationUnit.findAll(value)) {
            val amount = m.groupValues[1].toLong()
            millis +=
                when (m.groupValues[2]) {
                    "w" -> amount * 7 * 24 * 60 * 60 * 1000
                    "d" -> amount * 24 * 60 * 60 * 1000
                    "h" -> amount * 60 * 60 * 1000
                    "m" -> amount * 60 * 1000
                    "s" -> amount * 1000
                    else -> 0
                }
        }
        return millis
    }
}
