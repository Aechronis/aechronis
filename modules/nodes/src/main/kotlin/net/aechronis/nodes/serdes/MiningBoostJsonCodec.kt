package net.aechronis.nodes.serdes

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MiningBoostJsonCodec {
    fun encode(snapshot: MiningBoostSaveState): String = buildJsonObject {
        put("haste", encodeBoost(snapshot.haste))
        put("boost", encodeBoost(snapshot.boost))
    }.toString()

    private fun encodeBoost(boost: MiningBoostSaveState.BoostSaveState?): JsonElement = boost?.let {
        buildJsonObject {
            put("multiplier", it.multiplier)
            put("startedAt", it.startedAt)
            put("expiresAt", it.expiresAt)
        }
    } ?: JsonNull
}
