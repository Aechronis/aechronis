package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val GRAB_COOLDOWN_MILLIS = 30_000L
private const val MAX_SLOTS = 54

private data class FlagEntry(val id: UUID, val name: String)

private data class FlagsMenuSession(val inventory: Inventory, val entries: List<FlagEntry>)

/** `/flags` browser: any player can grab any nation's or other-flags.json flag, with a cooldown between grabs. */
object FlagsMenu {
    private val sessions = ConcurrentHashMap<UUID, FlagsMenuSession>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()

    fun init() {
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, this::onClick)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java, this::onClose)
    }

    fun open(player: Player) {
        // No pagination yet, so anything past a 6-row chest just gets dropped. Worth adding paging
        // if the number of flagged nations plus other-flags.json entries keeps growing past MAX_SLOTS.
        val nationFlags = Nation.all().mapNotNull { nation -> nation.flagUrl?.let { FlagEntry(nation.uuid, nation.name) } }
        val otherFlags = OtherFlags.all.map { FlagEntry(it.id, it.name) }
        val entries = (nationFlags + otherFlags).sortedBy { it.name.lowercase() }.take(MAX_SLOTS)
        val inventory = Inventory(inventoryTypeFor(entries.size.coerceAtLeast(1)), Component.text("Nation Flags", NamedTextColor.DARK_GREEN))
        entries.forEachIndexed { slot, entry -> inventory.setItemStack(slot, flagItemStack(entry)) }
        sessions[player.uuid] = FlagsMenuSession(inventory, entries)
        if (!player.openInventory(inventory)) sessions.remove(player.uuid)
    }

    fun closeAll() {
        sessions.toMap().forEach { (uuid, session) ->
            MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)?.let { player ->
                if (player.openInventory === session.inventory) player.closeInventory()
            }
        }
        sessions.clear()
    }

    // Base material is SPEAR, not TURTLE_SCUTE: vanilla renders it in a fixed two-handed held
    // pose instead of the normal one-handed swing/walk-bob, which is what was throwing the tall
    // pole model around so hard in third person. CROSSBOW does the same trick but combat's gun
    // system already keys aiming-state logic off Material.CROSSBOW, so flags use SPEAR instead
    // to avoid colliding with that. withItemModel() still fully replaces the visible geometry.
    private fun flagItemStack(entry: FlagEntry): ItemStack = ItemStack.of(Material.WOODEN_SPEAR)
        .withItemModel(NationFlagPack.flagModelId(entry.id))
        .withCustomName(Component.text("${entry.name} Flag", NamedTextColor.WHITE))

    private fun onClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        if (event.inventory !== session.inventory) return
        event.isCancelled = true
        val entry = session.entries.getOrNull(event.slot) ?: return

        val now = System.currentTimeMillis()
        cooldowns.values.removeIf { it <= now }
        val expiry = cooldowns[player.uuid]
        if (expiry != null && expiry > now) {
            Message.error(player, "You can grab another flag in ${(expiry - now) / 1000 + 1}s")
            return
        }
        if (!player.inventory.addItemStack(flagItemStack(entry))) {
            Message.error(player, "Your inventory is full")
            return
        }
        cooldowns[player.uuid] = now + GRAB_COOLDOWN_MILLIS
    }

    private fun onClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uuid] ?: return
        if (event.inventory === session.inventory) sessions.remove(event.player.uuid)
    }

    private fun inventoryTypeFor(count: Int): InventoryType = when {
        count <= 9 -> InventoryType.CHEST_1_ROW
        count <= 18 -> InventoryType.CHEST_2_ROW
        count <= 27 -> InventoryType.CHEST_3_ROW
        count <= 36 -> InventoryType.CHEST_4_ROW
        count <= 45 -> InventoryType.CHEST_5_ROW
        else -> InventoryType.CHEST_6_ROW
    }
}
