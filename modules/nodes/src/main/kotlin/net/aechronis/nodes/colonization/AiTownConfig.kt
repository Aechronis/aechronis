package net.aechronis.nodes.colonization

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Throwable

data class AiTownConfig(
    val controlled: Boolean = false,
    val enemyCount: Int = 0,
    val guns: List<String> = emptyList(),
    val throwables: List<String> = emptyList(),
) {
    val configured: Boolean get() = enemyCount > 0 && (guns.isNotEmpty() || throwables.isNotEmpty())
    val enabled: Boolean get() = controlled &&
        configured &&
        guns.all { Item.getFromName(it) is Gun } &&
        throwables.all { Item.getFromName(it) is Throwable }

    internal fun requireRegisteredItems() {
        val unknownGuns = guns.filter { Item.getFromName(it) !is Gun }
        require(unknownGuns.isEmpty()) { "Unknown gun(s): ${unknownGuns.joinToString(", ")}" }
        val unknownThrowables = throwables.filter { Item.getFromName(it) !is Throwable }
        require(unknownThrowables.isEmpty()) { "Unknown throwable(s): ${unknownThrowables.joinToString(", ")}" }
    }

    internal fun toJsonElement(): JsonObject = buildJsonObject {
        put("controlled", controlled)
        put("enemyCount", enemyCount)
        putJsonArray("guns") { guns.forEach { add(it) } }
        if (throwables.isNotEmpty()) putJsonArray("throwables") { throwables.forEach { add(it) } }
    }
}
