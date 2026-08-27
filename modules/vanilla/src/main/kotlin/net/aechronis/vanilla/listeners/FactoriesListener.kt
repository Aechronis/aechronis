package net.aechronis.vanilla.listeners

import net.aechronis.combat.events.ExplosionBlockChangeType
import net.aechronis.combat.events.ExplosionBlockDamageEvent
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Factories
import net.aechronis.vanilla.objects.consumeStationInteraction
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent

object FactoriesListener {
    fun onPlace(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) return
        val item = if (event.hand == PlayerHand.MAIN) event.player.itemInMainHand else event.player.itemInOffHand
        Factories.onPlace(event.player, event.instance, event.blockPosition, item)
    }

    fun onBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        if (Factories.onBreak(event.player, event.instance, event.blockPosition)) event.isCancelled = true
    }

    fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.isCancelled || !Factories.isFactory(event.instance, event.blockPosition)) return
        if (!event.consumeStationInteraction()) return
        event.isCancelled = true
        Factories.onInteract(event.player, event.instance, event.blockPosition)
    }

    fun onExplosion(event: ExplosionBlockDamageEvent) {
        if (event.isCancelled) return
        if (
            event.changes.any { change ->
                change.type == ExplosionBlockChangeType.DAMAGE && Factories.blocksExplosion(event.instance, change.position)
            }
        ) {
            event.isCancelled = true
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java, FactoriesListener::onPlace)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, FactoriesListener::onBreak)
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, FactoriesListener::onInteract)
        Vanilla.eventNode.addListener(ExplosionBlockDamageEvent::class.java, FactoriesListener::onExplosion)
    }
}
