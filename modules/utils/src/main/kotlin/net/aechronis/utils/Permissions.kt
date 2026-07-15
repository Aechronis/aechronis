package net.aechronis.utils

import net.luckperms.api.LuckPermsProvider
import net.minestom.server.entity.Player

object Perms {
    val debug: Boolean = System.getenv("DEBUG")?.toBoolean() == true
}

/**
 * Checks this player's LuckPerms data for [permission].
 *
 * A missing provider or user is treated as a denial. A `null` permission does
 * not restrict access, which allows callers to use optional permission nodes.
 */
fun Player.hasPermission(permission: String?): Boolean {
    if (permission == null) return true

    return try {
        LuckPermsProvider
            .get()
            .userManager
            .getUser(uuid)
            ?.cachedData
            ?.permissionData
            ?.checkPermission(permission)
            ?.asBoolean() == true
    } catch (_: Exception) {
        Perms.debug
    }
}
