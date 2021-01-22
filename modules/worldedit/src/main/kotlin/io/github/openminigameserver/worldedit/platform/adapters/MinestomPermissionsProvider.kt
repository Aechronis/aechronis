package io.github.openminigameserver.worldedit.platform.adapters

import net.minestom.server.entity.Player

object MinestomPermissionsProvider {

    fun hasPermission(player: Player, permission: String): Boolean {
        return player.hasPermission(permission) || player.hasPermission("worldedit.*")
    }
}
