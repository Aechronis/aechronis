package net.aechronis.vanilla.objects

import net.minestom.server.event.player.PlayerBlockInteractEvent

internal fun PlayerBlockInteractEvent.consumeStationInteraction(): Boolean {
    if (isCancelled) return false
    val player = player
    if (player.isSneaking && (!player.itemInMainHand.isAir || !player.itemInOffHand.isAir)) return false

    isCancelled = true
    isBlockingItemUse = true
    return true
}
