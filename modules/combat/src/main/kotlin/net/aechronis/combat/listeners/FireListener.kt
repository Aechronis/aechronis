package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.event.player.PlayerStartDiggingEvent

object FireListener {
    private fun onPlayerHandAnimation(event: PlayerHandAnimationEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val player = event.player
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return

        gun.fire(player)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, FireListener::onPlayerHandAnimation)
        // The client can instantly break our fake sculk veins. Never let that alter the
        // real world (including real sculk veins and zero-hardness blocks).
        // Run before ore/drop handlers, which may act on a break event themselves.
        val miningEvents = EventNode.all("combat-gun-mining").setPriority(Int.MIN_VALUE)
        miningEvents.addListener(PlayerStartDiggingEvent::class.java) { event ->
            if (Item.getFromItemStack(event.player.itemInMainHand) is Gun) event.isCancelled = true
        }
        miningEvents.addListener(PlayerBlockBreakEvent::class.java) { event ->
            if (Item.getFromItemStack(event.player.itemInMainHand) is Gun) event.isCancelled = true
        }
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), miningEvents)
    }
}
