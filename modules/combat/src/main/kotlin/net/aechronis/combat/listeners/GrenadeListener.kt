package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Grenade
import net.aechronis.combat.objects.Item
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.event.player.PlayerSwapItemEvent

object GrenadeListener {
    private fun onPlayerHandAnimation(event: PlayerHandAnimationEvent) {
        val player = event.player
        val grenade = Item.getFromItemStack(player.itemInMainHand) as? Grenade ?: return
        grenade.use(player)
    }

    private fun onPlayerDeath(event: PlayerDeathEvent) {
        Grenade.detonateInHand(event.player)
    }

    private fun onHeldSlotChange(event: PlayerChangeHeldSlotEvent) {
        if (Combat.armedGrenades.containsKey(event.player)) event.isCancelled = true
    }

    private fun onSwapItem(event: PlayerSwapItemEvent) {
        if (Combat.armedGrenades.containsKey(event.player)) event.isCancelled = true
    }

    private fun onInventoryClick(event: InventoryPreClickEvent) {
        if (Combat.armedGrenades.containsKey(event.player)) event.isCancelled = true
    }

    fun init() {
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, GrenadeListener::onPlayerHandAnimation)
        Combat.eventNode.addListener(PlayerDeathEvent::class.java, GrenadeListener::onPlayerDeath)
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, GrenadeListener::onHeldSlotChange)
        Combat.eventNode.addListener(PlayerSwapItemEvent::class.java, GrenadeListener::onSwapItem)
        Combat.eventNode.addListener(InventoryPreClickEvent::class.java, GrenadeListener::onInventoryClick)
    }
}
