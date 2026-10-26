package net.aechronis.economy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal object MiningBoostJsonCodec {
    fun encode(snapshot: MiningBoostSaveState): String =
        buildJsonObject {
            put("haste", encodeBoost(snapshot.haste))
            put("boost", encodeBoost(snapshot.boost))
        }.toString()

    fun decode(
        json: JsonObject,
        maxHasteMultiplier: Int,
        maxMiningMultiplier: Int,
    ): MiningBoostSaveState {
        val now = System.currentTimeMillis()
        return MiningBoostSaveState(
            decodeBoost(json["haste"], maxHasteMultiplier, now),
            decodeBoost(json["boost"], maxMiningMultiplier, now),
        )
    }

    private fun encodeBoost(boost: MiningBoostSaveState.BoostSaveState?): JsonElement =
        boost?.let {
            buildJsonObject {
                put("multiplier", it.multiplier)
                put("startedAt", it.startedAt)
                put("expiresAt", it.expiresAt)
            }
        } ?: JsonNull

    private fun decodeBoost(
        value: JsonElement?,
        maxMultiplier: Int,
        now: Long,
    ): MiningBoostSaveState.BoostSaveState? {
        if (value == JsonNull) return null
        require(value is JsonObject) { "Mining boost record must be an object or null" }
        val multiplier = requireNotNull(value["multiplier"]) { "Missing mining boost multiplier" }.jsonPrimitive.int
        val startedAt = requireNotNull(value["startedAt"]) { "Missing mining boost start time" }.jsonPrimitive.long
        val expiresAt = requireNotNull(value["expiresAt"]) { "Missing mining boost expiry time" }.jsonPrimitive.long
        require(multiplier in 1..maxMultiplier) { "Mining boost multiplier must be between 1 and $maxMultiplier" }
        require(startedAt >= 0L && expiresAt > startedAt) { "Mining boost timestamps must describe a positive duration" }
        if (expiresAt <= now) return null
        return MiningBoostSaveState.BoostSaveState(multiplier, startedAt, expiresAt)
    }
}
