package net.aechronis.vanilla.utils

import net.luckperms.api.LuckPermsProvider
import net.minestom.server.entity.Player

fun hasPermission(
    player: Player,
    permission: String,
): Boolean =
    try {
        LuckPermsProvider
            .get()
            .userManager
            .getUser(player.uuid)
            ?.cachedData
            ?.permissionData
            ?.checkPermission(permission)
            ?.asBoolean()
            ?: false
    } catch (_: Exception) {
        false
    }