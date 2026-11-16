package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.SwingAnimation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val GRAB_COOLDOWN_MILLIS = 30_000L
private const val MAX_SLOTS = 54
private const val FLAGS_PER_PAGE = 45
private const val PREVIOUS_PAGE_SLOT = 45
private const val NEXT_PAGE_SLOT = 53

private data class FlagEntry(val id: UUID, val name: String)

private data class FlagsMenuSession(val inventory: Inventory, val entries: List<FlagEntry>, val page: Int, val lastPage: Int)

/** `/flags` browser: any player can grab any nation's or other-flags.json flag, with a cooldown between grabs. */
object FlagsMenu {
    private val sessions = ConcurrentHashMap<UUID, FlagsMenuSession>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()

    fun init() {
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, this::onClick)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java, this::onClose)
    }

    fun open(player: Player, requestedPage: Int = 0) {
        val nationFlags = Nation.all().mapNotNull { nation -> nation.flagUrl?.let { FlagEntry(nation.uuid, nation.name) } }
        val otherFlags = OtherFlags.all.map { FlagEntry(it.id, it.name) }
        val flags = (nationFlags + otherFlags).sortedBy { it.name.lowercase() }
        // Flags that fit in one chest fill it; past that the bottom row is kept for the page buttons.
        val paged = flags.size > MAX_SLOTS
        val lastPage = if (paged) (flags.size - 1) / FLAGS_PER_PAGE else 0
        val page = requestedPage.coerceIn(0, lastPage)
        val entries = if (paged) flags.drop(page * FLAGS_PER_PAGE).take(FLAGS_PER_PAGE) else flags
        val inventory = Inventory(
            if (paged) InventoryType.CHEST_6_ROW else inventoryTypeFor(entries.size.coerceAtLeast(1)),
            Component.text(if (paged) "Nation Flags ${page + 1}/${lastPage + 1}" else "Nation Flags", NamedTextColor.DARK_GREEN),
        )
        entries.forEachIndexed { slot, entry -> inventory.setItemStack(slot, flagItemStack(entry)) }
        if (page > 0) inventory.setItemStack(PREVIOUS_PAGE_SLOT, navigationItem("Previous page"))
        if (page < lastPage) inventory.setItemStack(NEXT_PAGE_SLOT, navigationItem("Next page"))
        val session = FlagsMenuSession(inventory, entries, page, lastPage)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    fun closeAll() {
        sessions.toMap().forEach { (uuid, session) ->
            MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)?.let { player ->
                if (player.openInventory === session.inventory) player.closeInventory()
            }
        }
        sessions.clear()
    }

    // Disable the base spear's right-click use and attack swing animation on flag items.
    private fun flagItemStack(entry: FlagEntry): ItemStack = ItemStack.of(Material.WOODEN_SPEAR)
        .with(DataComponents.SWING_ANIMATION, SwingAnimation(SwingAnimation.Type.NONE, 6))
        .without(DataComponents.KINETIC_WEAPON)
        .withItemModel(NationFlagPack.flagModelId(entry.id))
        .withCustomName(Component.text("${entry.name} Flag", NamedTextColor.WHITE))

    private fun navigationItem(name: String): ItemStack = ItemStack.of(Material.ARROW).withCustomName(Component.text(name, NamedTextColor.AQUA))

    private fun openPage(player: Player, session: FlagsMenuSession, page: Int) {
        ModuleScheduler.scheduleNextTick {
            if (player.isOnline && player.openInventory === session.inventory) open(player, page)
        }
    }

    private fun onClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        if (event.inventory !== session.inventory) return
        event.isCancelled = true
        if (event.slot == PREVIOUS_PAGE_SLOT && session.page > 0) return openPage(player, session, session.page - 1)
        if (event.slot == NEXT_PAGE_SLOT && session.page < session.lastPage) return openPage(player, session, session.page + 1)
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
