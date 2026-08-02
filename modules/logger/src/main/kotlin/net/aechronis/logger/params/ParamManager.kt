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
    private val durationRegex = Regex("^(\\d+(?:\\.\\d+)?[wdhms])+$")
    private val durationUnit = Regex("(\\d+(?:\\.\\d+)?)([wdhms])")

    fun parse(tokens: Array<String>): ParseResult {
        var users: List<String> = emptyList()
        var since: Long? = null
        var until: Long? = null
        var radius: Int? = null
        var global = false
        var chunkRadius: Int? = null
        val rawActionTokens = mutableListOf<String>()
        var include: List<String> = emptyList()
        var exclude: List<String> = emptyList()
        var source: String? = null
        var context: String? = null
        var origin: String? = null

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
                    val range = value.replace(",", "").split('-')
                    if (range.size > 2) return ParseResult.Err("Invalid time: '$value'")
                    val durations = range.map { parseDuration(it) ?: return ParseResult.Err("Invalid time: '$value' (try 1w2d3h4m5s)") }
                    val now = System.currentTimeMillis()
                    since = now - durations.max()
                    until = if (durations.size == 2) now - durations.min() else null
                }

                ParamKey.RADIUS -> {
                    if (value.equals("#global", true) || value.equals("global", true)) {
                        radius = null
                        global = true
                        continue
                    }
                    val r = value.toIntOrNull()
                    if (r == null || r !in 0..MAX_RADIUS) return ParseResult.Err("Invalid radius: '$value'")
                    radius = r
                    global = false
                }

                ParamKey.CHUNK_RADIUS -> {
                    val cr = value.toIntOrNull()
                    if (cr == null || cr !in 1..MAX_CHUNK_RADIUS) {
                        return ParseResult.Err("Invalid chunk radius: '$value'")
                    }
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

                ParamKey.SOURCE -> {
                    source = value.trim()
                }

                ParamKey.CONTEXT -> {
                    context = value.trim()
                }

                ParamKey.ORIGIN -> {
                    origin = value.trim()
                }
            }
        }

        val splitActionTokens =
            rawActionTokens
                .flatMap { it.split(',') }
                .map { it.trim() }
        val actionTokens = splitActionTokens.filter { it.isNotBlank() }

        if (source != null) {
            if (context != null) return ParseResult.Err("s:/c: cannot be combined")
            if (include.isNotEmpty() || exclude.isNotEmpty()) {
                return ParseResult.Err("i:/e: are not supported with s:<source> lookups")
            }
            return ParseResult.Ok(
                LookupQuery.Feature(FeatureLookupParams(source, users, since, until, radius, chunkRadius, actionTokens, origin)),
            )
        }

        if (splitActionTokens.any { it.isBlank() }) {
            return ParseResult.Err("Invalid action: ''")
        }

        var actions: MutableSet<BlockAction>? = null
        for (actionToken in actionTokens) {
            val mapped = mapAction(actionToken) ?: return ParseResult.Err("Invalid action: '$actionToken'")
            actions = (actions ?: mutableSetOf()).apply { addAll(mapped) }
        }

        return ParseResult.Ok(
            LookupQuery.Block(
                LookupParams(
                    users = users,
                    since = since,
                    until = until,
                    radius = radius,
                    chunkRadius = chunkRadius,
                    actions = actions,
                    include = include,
                    exclude = exclude,
                    source = context,
                    origin = origin,
                    global = global,
                ),
            ),
        )
    }

    private const val MAX_RADIUS = 30_000_000
    private const val MAX_CHUNK_RADIUS = 1_875_001

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
            val amount = m.groupValues[1].toBigDecimalOrNull() ?: return null
            val unitMillis =
                when (m.groupValues[2]) {
                    "w" -> 7 * 24 * 60 * 60 * 1000L
                    "d" -> 24 * 60 * 60 * 1000L
                    "h" -> 60 * 60 * 1000L
                    "m" -> 60 * 1000L
                    "s" -> 1000L
                    else -> return null
                }
            val partMillis = runCatching { amount.multiply(unitMillis.toBigDecimal()).longValueExact() }.getOrNull() ?: return null
            if (millis > Long.MAX_VALUE - partMillis) return null
            millis += partMillis
        }
        return millis
    }
}
