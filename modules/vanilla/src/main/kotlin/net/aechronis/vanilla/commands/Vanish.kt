package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.minestom.server.entity.Player
import net.aechronis.vanilla.managers.Vanish as VanishManager

class Vanish : Command("vanish") {
    override fun hasPermission(
        player: Player,
        permission: String?,
    ): Boolean = VanishManager.isVanished(player) || VanishManager.level(player) > 0

    init {
        setDefaultExecutor { player: Player, _ -> VanishManager.toggle(player) }
    }
}
