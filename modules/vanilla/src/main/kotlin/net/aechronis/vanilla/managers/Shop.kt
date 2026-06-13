package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.ShopClickListener
import net.aechronis.vanilla.listeners.ShopKillListener
import net.aechronis.vanilla.objects.ShopItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.tag.Tag
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Shop {
    val POINTS_TAG: Tag<Int> = Tag.Integer("shop_points")
    val openInventories = ConcurrentHashMap<Inventory, UUID>()
    val playerCooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, Long>>()

    fun init() {
        val timeStart = System.currentTimeMillis()
        ShopClickListener.init()
        ShopKillListener.init()
        val timeEnd = System.currentTimeMillis()
        println("Shop enabled in ${timeEnd - timeStart}ms")
    }

    fun openShop(player: Player) {
        val items = Vanilla.config!!.shopItems
        val inv = Inventory(InventoryType.CHEST_3_ROW, Component.text("Shop"))
        val points = player.getTag(POINTS_TAG) ?: 0
        val cooldowns = playerCooldowns.getOrPut(player.uuid) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()

        for ((index, shopItem) in items.withIndex()) {
            val lastPurchase = cooldowns[index]
            val cooldownMs = shopItem.cooldownTicks * 50L
            val remainingMs = if (lastPurchase != null) maxOf(0L, cooldownMs - (now - lastPurchase)) else 0L
            inv.setItemStack(index, buildItemDisplay(shopItem, points, remainingMs))
        }

        openInventories[inv] = player.uuid
        player.openInventory(inv)
    }

    fun buildItemDisplay(
        shopItem: ShopItem,
        points: Int,
        cooldownRemainingMs: Long,
    ): ItemStack {
        val canAfford = points >= shopItem.cost
        val onCooldown = cooldownRemainingMs > 0

        val costColor = if (canAfford) NamedTextColor.YELLOW else NamedTextColor.RED
        val cooldownLine =
            if (onCooldown) {
                Component.text("Cooldown: ${"%.1f".format(cooldownRemainingMs / 1000.0)}s", NamedTextColor.RED)
            } else {
                Component.text("Ready to buy", NamedTextColor.GREEN)
            }

        return shopItem.itemStack.with(
            DataComponents.LORE,
            listOf(
                Component.text("Cost: ${shopItem.cost} points", costColor),
                cooldownLine,
            ),
        )
    }

    fun restock() {
        playerCooldowns.clear()
    }
}
