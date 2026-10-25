package net.aechronis.killshop.listeners

import net.aechronis.killshop.KillShop
import net.aechronis.killshop.KillShopPoints
import net.aechronis.server.modules.ModuleEvents
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.inventory.Inventory
import java.util.concurrent.ConcurrentHashMap

object ShopListener {
    private val eventNode = EventNode.all("killshop").setPriority(1)

    fun onPreClick(event: InventoryPreClickEvent) {
        val inv = event.inventory as? Inventory ?: return
        if (!KillShop.openInventories.containsKey(inv)) return
        event.isCancelled = true

        val player = event.player
        val slot = event.slot
        val items = KillShop.config.shopItems
        if (slot < 0 || slot >= items.size) return

        val shopItem = items[slot]
        val now = System.currentTimeMillis()
        val cooldowns = KillShop.playerCooldowns.getOrPut(player.uuid) { ConcurrentHashMap() }
        val expiry = cooldowns[slot]
        val cooldownMs = shopItem.cooldownTicks * 50L

        if (expiry != null && expiry > now) {
            val remainingSecs = "%.1f".format((expiry - now) / 1000.0)
            player.sendMessage(Component.text("This item is on cooldown for ${remainingSecs}s", NamedTextColor.RED))
            return
        }
        if (expiry != null) cooldowns.remove(slot, expiry)

        val points = KillShopPoints.balance(player)
        if (points < shopItem.cost) {
            player.sendMessage(Component.text("You need ${shopItem.cost} points but only have $points", NamedTextColor.RED))
            return
        }

        if (!player.inventory.addItemStack(shopItem.itemStack)) {
            player.sendMessage(Component.text("Your inventory is full", NamedTextColor.RED))
            return
        }

        KillShopPoints.setBalance(player, points - shopItem.cost)
        cooldowns[slot] = now + cooldownMs
    }

    fun onClose(event: InventoryCloseEvent) {
        val inv = event.inventory as? Inventory ?: return
        KillShop.openInventories.remove(inv)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        KillShop.playerCooldowns.remove(event.player.uuid)
        KillShop.openInventories.entries.removeIf { it.value == event.player.uuid }
        KillShopPoints.save(KillShopPoints.snapshot()).whenComplete { _, error ->
            if (error != null) System.err.println("Failed to save kill-shop points: ${error.message}")
        }
    }

    fun onDeath(event: PlayerDeathEvent) {
        val victim = event.player
        val killer = event.player.lastDamageSource?.attacker as? Player ?: return
        if (killer.uuid == victim.uuid) return
        KillShopPoints.setBalance(killer, KillShopPoints.balance(killer) + 1)
    }

    fun init() {
        eventNode.addListener(PlayerDeathEvent::class.java, ShopListener::onDeath)
        eventNode.addListener(InventoryPreClickEvent::class.java, ShopListener::onPreClick)
        eventNode.addListener(InventoryCloseEvent::class.java, ShopListener::onClose)
        eventNode.addListener(PlayerDisconnectEvent::class.java, ShopListener::onDisconnect)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }
}
