package net.aechronis.bundles.listeners

import net.aechronis.bundles.Bundles
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerUseItemEvent

object BundleListener {
    private val eventNode = EventNode.all("bundles")

    fun onUseItem(event: PlayerUseItemEvent) {
        if (!Bundles.isBundle(event.itemStack)) return

        event.isCancelled = true
        Bundles.use(event.player, event.hand)
    }

    fun init() {
        eventNode.addListener(PlayerUseItemEvent::class.java, BundleListener::onUseItem)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }
}
