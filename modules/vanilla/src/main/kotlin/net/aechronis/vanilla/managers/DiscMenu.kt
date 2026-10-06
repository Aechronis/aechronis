package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.objects.MusicDisc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val GRAB_COOLDOWN_MILLIS = 30_000L
private const val MAX_SLOTS = 54

private data class DiscMenuSession(
    val inventory: Inventory,
    val discs: List<MusicDisc>,
)

/** `/disc` browser: any player can grab any custom music disc, with a cooldown between grabs. */
object DiscMenu {
    private val sessions = ConcurrentHashMap<UUID, DiscMenuSession>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()

    fun init() {
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, this::onClick)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, this::onClose)
    }

    fun open(player: Player) {
        // No pagination yet, so anything past a 6-row chest just gets dropped
        val discs = Music.discs.sortedBy { it.name.lowercase() }.take(MAX_SLOTS)
        if (discs.isEmpty()) {
            player.sendMessage(Component.text("No music discs are available yet", NamedTextColor.RED))
            return
        }
        val inventory = Inventory(inventoryTypeFor(discs.size), Component.text("Music Discs", NamedTextColor.DARK_PURPLE))
        discs.forEachIndexed { slot, disc -> inventory.setItemStack(slot, Music.itemFor(disc)) }
        sessions[player.uuid] = DiscMenuSession(inventory, discs)
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

    private fun onClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        if (event.inventory !== session.inventory) return
        event.isCancelled = true
        val disc = session.discs.getOrNull(event.slot) ?: return

        val now = System.currentTimeMillis()
        cooldowns.values.removeIf { it <= now }
        val expiry = cooldowns[player.uuid]
        if (expiry != null && expiry > now) {
            player.sendMessage(Component.text("You can grab another disc in ${(expiry - now) / 1000 + 1}s", NamedTextColor.RED))
            return
        }
        if (!player.inventory.addItemStack(Music.itemFor(disc))) {
            player.sendMessage(Component.text("Your inventory is full", NamedTextColor.RED))
            return
        }
        cooldowns[player.uuid] = now + GRAB_COOLDOWN_MILLIS
    }

    private fun onClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uuid] ?: return
        if (event.inventory === session.inventory) sessions.remove(event.player.uuid)
    }

    private fun inventoryTypeFor(count: Int): InventoryType =
        when {
            count <= 9 -> InventoryType.CHEST_1_ROW
            count <= 18 -> InventoryType.CHEST_2_ROW
            count <= 27 -> InventoryType.CHEST_3_ROW
            count <= 36 -> InventoryType.CHEST_4_ROW
            count <= 45 -> InventoryType.CHEST_5_ROW
            else -> InventoryType.CHEST_6_ROW
        }
}
