package io.github.openminigameserver.worldedit.platform.adapters

import net.minestom.server.entity.Player

object MinestomPermissionsProvider {
    // Minestom no longer has a string-based permission-node system (only an integer op
    // permissionLevel), so we fall back to the vanilla "cheats allowed" op level (2) as the
    // closest built-in equivalent of the old "worldedit.*" wildcard.
    fun hasPermission(
        player: Player,
        permission: String,
    ): Boolean = player.permissionLevel >= 2
}
