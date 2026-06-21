package net.aechronis.combat.utils

import net.luckperms.api.LuckPermsProvider
import net.minestom.server.entity.Player

val permissionBypass: MutableSet<String> = mutableSetOf()

fun hasPermission(
    player: Player,
    permission: String,
): Boolean {
    if (player.username in permissionBypass) return true
    return try {
        LuckPermsProvider
            .get()
            .userManager
            .getUser(player.uuid)
            ?.cachedData
            ?.permissionData
            ?.checkPermission(permission)
            ?.asBoolean()
            ?: true
    } catch (_: Exception) {
        true
    }
}
