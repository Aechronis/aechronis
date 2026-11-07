package net.aechronis.settings

import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One per-player option shown in the /settings menu. Clicking cycles through [values] in order.
 * [set] returns false when the change could not be applied.
 */
class PlayerSetting(
    val title: String,
    val icon: Material,
    val values: List<String>,
    val get: (Player) -> String,
    val set: (Player, String) -> Boolean,
) {
    companion object {
        fun toggle(
            title: String,
            icon: Material,
            get: (Player) -> Boolean,
            set: (Player, Boolean) -> Boolean,
        ): PlayerSetting {
            val read = { player: Player -> if (get(player)) "on" else "off" }
            val write = { player: Player, value: String -> set(player, value == "on") }
            return PlayerSetting(title, icon, listOf("on", "off"), read, write)
        }
    }
}

/** Modules register their settings on initialize and unregister the same instances on shutdown. Menu order is registration order. */
object PlayerSettings {
    private val entries = CopyOnWriteArrayList<PlayerSetting>()

    fun register(vararg settings: PlayerSetting) = entries.addAll(settings)

    fun unregister(vararg settings: PlayerSetting) = entries.removeAll(settings.toSet())

    fun all(): List<PlayerSetting> = entries
}
