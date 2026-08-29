package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.ShopListener
import net.aechronis.vanilla.objects.ShopItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.tag.Tag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object KillShop {
    val POINTS_TAG: Tag<Int> = Tag.Integer("shop_points")
    val openInventories = ConcurrentHashMap<Inventory, UUID>()
    val playerCooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, Long>>()

    fun init() {
        val timeStart = System.currentTimeMillis()
        ShopListener.init()
        val timeEnd = System.currentTimeMillis()
        println("├─ Shop enabled in ${timeEnd - timeStart}ms")
    }

    fun openShop(player: Player) {
        val items = Vanilla.config.shopConfig.shopItems
        val inv = Inventory(InventoryType.CHEST_6_ROW, Component.text("Shop"))
        val points = player.getTag(POINTS_TAG) ?: 0
        val cooldowns = playerCooldowns.getOrPut(player.uuid) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()

        for ((index, shopItem) in items.withIndex()) {
            val expiry = cooldowns[index]
            val remainingMs = expiry?.let { (it - now).coerceAtLeast(0L) } ?: 0L
            if (expiry != null && remainingMs == 0L) cooldowns.remove(index, expiry)
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

    internal fun captureTransientState(now: Long = System.currentTimeMillis()): ByteArray {
        val cooldowns =
            playerCooldowns
                .flatMap { (uuid, bySlot) ->
                    bySlot.mapNotNull { (slot, expiry) ->
                        CooldownState(uuid, slot, expiry).takeIf { expiry > now }
                    }
                }.sortedWith(compareBy<CooldownState> { it.uuid.toString() }.thenBy(CooldownState::slot))
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(TRANSIENT_STATE_VERSION)
                output.writeInt(cooldowns.size)
                cooldowns.forEach { cooldown ->
                    output.writeLong(cooldown.uuid.mostSignificantBits)
                    output.writeLong(cooldown.uuid.leastSignificantBits)
                    output.writeInt(cooldown.slot)
                    output.writeLong(cooldown.expiry)
                }
            }
            bytes.toByteArray()
        }
    }

    internal fun restoreTransientState(
        payload: ByteArray?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (payload == null) return
        val restored = decodeTransientState(payload)
        playerCooldowns.clear()
        restored.forEach { cooldown ->
            if (cooldown.expiry > now) {
                playerCooldowns
                    .getOrPut(cooldown.uuid) { ConcurrentHashMap() }[cooldown.slot] = cooldown.expiry
            }
        }
    }

    fun shutdown() {
        openInventories.keys.toList().forEach { inventory -> inventory.viewers.toList().forEach { it.closeInventory() } }
        openInventories.clear()
        playerCooldowns.clear()
    }

    private fun decodeTransientState(payload: ByteArray): List<CooldownState> =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == TRANSIENT_STATE_VERSION) { "Unsupported shop-cooldown transient-state version" }
            val size = input.readInt()
            require(size in 0..MAX_TRANSIENT_ENTRIES) { "Invalid shop-cooldown transient-state size: $size" }
            val seen = hashSetOf<Pair<UUID, Int>>()
            List(size) {
                val uuid = UUID(input.readLong(), input.readLong())
                val slot = input.readInt()
                require(slot in 0 until MAX_SHOP_SLOTS) { "Invalid shop cooldown slot for $uuid: $slot" }
                val expiry = input.readLong()
                require(expiry >= 0L) { "Invalid shop cooldown expiry for $uuid" }
                require(seen.add(uuid to slot)) { "Duplicate shop cooldown for $uuid slot $slot" }
                CooldownState(uuid, slot, expiry)
            }.also {
                require(input.available() == 0) { "Trailing shop-cooldown transient-state data" }
            }
        }

    private data class CooldownState(
        val uuid: UUID,
        val slot: Int,
        val expiry: Long,
    )

    private const val TRANSIENT_STATE_VERSION = 1
    private const val MAX_TRANSIENT_ENTRIES = 100_000
    private const val MAX_SHOP_SLOTS = 54
}
