package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Bundles
import net.minestom.server.event.player.PlayerUseItemEvent

object BundleListener {
    fun onUseItem(event: PlayerUseItemEvent) {
        if (!Bundles.isBundle(event.itemStack)) return

        event.isCancelled = true
        Bundles.use(event.player, event.hand)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerUseItemEvent::class.java, BundleListener::onUseItem)
    }
}
