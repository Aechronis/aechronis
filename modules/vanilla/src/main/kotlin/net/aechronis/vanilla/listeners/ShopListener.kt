package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.KillShop
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.inventory.Inventory
import java.util.concurrent.ConcurrentHashMap

object ShopListener {
    fun onPreClick(event: InventoryPreClickEvent) {
        val inv = event.inventory as? Inventory ?: return
        if (!KillShop.openInventories.containsKey(inv)) return
        event.isCancelled = true

        val player = event.player
        val slot = event.slot
        val items = Vanilla.config.shopItems
        if (slot < 0 || slot >= items.size) return

        val shopItem = items[slot]
        val now = System.currentTimeMillis()
        val cooldowns = KillShop.playerCooldowns.getOrPut(player.uuid) { ConcurrentHashMap() }
        val lastPurchase = cooldowns[slot]
        val cooldownMs = shopItem.cooldownTicks * 50L

        if (lastPurchase != null && (now - lastPurchase) < cooldownMs) {
            val remainingSecs = "%.1f".format((cooldownMs - (now - lastPurchase)) / 1000.0)
            player.sendMessage(Component.text("This item is on cooldown for ${remainingSecs}s", NamedTextColor.RED))
            return
        }

        val points = player.getTag(KillShop.POINTS_TAG) ?: 0
        if (points < shopItem.cost) {
            player.sendMessage(Component.text("You need ${shopItem.cost} points but only have $points", NamedTextColor.RED))
            return
        }

        if (!player.inventory.addItemStack(shopItem.itemStack)) {
            player.sendMessage(Component.text("Your inventory is full", NamedTextColor.RED))
            return
        }

        player.setTag(KillShop.POINTS_TAG, points - shopItem.cost)
        cooldowns[slot] = now
    }

    fun onClose(event: InventoryCloseEvent) {
        val inv = event.inventory as? Inventory ?: return
        KillShop.openInventories.remove(inv)
    }

    fun onDeath(event: PlayerDeathEvent) {
        val killer = event.player.lastDamageSource?.attacker as? Player ?: return
        killer.setTag(KillShop.POINTS_TAG, (killer.getTag(KillShop.POINTS_TAG) ?: 0) + 1)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, ShopListener::onDeath)
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, ShopListener::onPreClick)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, ShopListener::onClose)
    }
}
