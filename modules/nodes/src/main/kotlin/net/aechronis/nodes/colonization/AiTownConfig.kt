package net.aechronis.nodes.colonization

import com.google.gson.JsonPrimitive
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item

data class AiTownConfig(
    val controlled: Boolean = false,
    val enemyCount: Int = 0,
    val guns: List<String> = emptyList(),
) {
    val configured: Boolean get() = enemyCount > 0 && guns.isNotEmpty()
    val enabled: Boolean get() = controlled && configured && guns.all { Item.getFromName(it) is Gun }

    internal fun requireRegisteredGuns() {
        val unknown = guns.filter { Item.getFromName(it) !is Gun }
        require(unknown.isEmpty()) { "Unknown gun(s): ${unknown.joinToString(", ")}" }
    }

    internal fun toJsonString(): String {
        val gunsJson = guns.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }
        return "{" +
            "\"controlled\":$controlled," +
            "\"enemyCount\":$enemyCount," +
            "\"guns\":$gunsJson" +
            "}"
    }
}
