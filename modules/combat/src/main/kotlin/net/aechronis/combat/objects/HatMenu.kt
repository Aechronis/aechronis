package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.listeners.HatListener
import net.aechronis.combat.storage.HatCollection
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
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object HatMenu {
    private const val PAGE_SIZE = 45

    private data class Session(
        val inventory: Inventory,
        val page: Int,
        val hats: List<HatInstance>,
        val hasNext: Boolean,
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun init() {
        Combat.eventNode.addListener(InventoryPreClickEvent::class.java, ::onClick)
        Combat.eventNode.addListener(InventoryCloseEvent::class.java) { event ->
            val session = sessions[event.player.uuid] ?: return@addListener
            if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
        }
    }

    fun open(
        player: Player,
        requestedPage: Int = 0,
    ) {
        val hats = HatCollection.hats(player.uuid)
        val lastPage = ((hats.size - 1) / PAGE_SIZE).coerceAtLeast(0)
        val page = requestedPage.coerceIn(0, lastPage)
        val inventory = Inventory(InventoryType.CHEST_6_ROW, Component.text("Your hats • ${page + 1}/${lastPage + 1}"))
        val pageHats = hats.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val equipped = HatCollection.equipped(player.uuid)
        pageHats.forEachIndexed { slot, hat ->
            val selected = equipped?.number == hat.number
            inventory.setItemStack(
                slot,
                hat.preview
                    .with(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, selected)
                    .withLore(Component.text(if (selected) "Equipped" else "Click to equip", NamedTextColor.GRAY)),
            )
        }
        if (hats.isEmpty()) inventory.setItemStack(22, button(Material.PAPER, Component.text("You do not own any hats",
            NamedTextColor.DARK_RED)))
        if (page > 0) inventory.setItemStack(45, button(Material.ARROW, Component.text("Previous page")))
        inventory.setItemStack(49, button(Material.BARRIER, Component.text("Remove hat", NamedTextColor.RED)))
        if (page < lastPage) inventory.setItemStack(53, button(Material.ARROW, Component.text("Next page")))
        val session = Session(inventory, page, pageHats, page < lastPage)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    fun refresh(player: Player) {
        val session = sessions[player.uuid] ?: return
        if (player.openInventory === session.inventory) open(player, session.page)
    }

    fun close(player: Player) {
        sessions.remove(player.uuid)
    }

    fun shutdown() {
        sessions.toMap().forEach { (uuid, session) ->
            val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)
            if (player?.openInventory === session.inventory) player.closeInventory()
        }
        sessions.clear()
    }

    private fun onClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        if (player.openInventory !== session.inventory) return
        // Cancel every inventory action, including shifts, drags, number keys and drops.
        event.isCancelled = true
        if (event.inventory !== session.inventory || (event.click !is Click.Left && event.click !is Click.Right)) return
        val slot = event.slot
        ModuleScheduler.scheduleNextTick {
            if (!player.isOnline || sessions[player.uuid] !== session || player.openInventory !== session.inventory) return@scheduleNextTick
            when (slot) {
                45 -> if (session.page > 0) open(player, session.page - 1)
                53 -> if (session.hasNext) open(player, session.page + 1)
                49 -> {
                    HatCollection.equip(player.uuid, null)
                    HatListener.refresh(player)
                    open(player, session.page)
                }
                in session.hats.indices -> {
                    val hat = session.hats[slot]
                    if (HatCollection.owns(player.uuid, hat)) {
                        HatCollection.equip(player.uuid, hat)
                        HatListener.refresh(player)
                    }
                    open(player, session.page)
                }
            }
        }
    }

    private fun button(
        material: Material,
        name: Component,
    ): ItemStack = ItemStack.of(material).withCustomName(name)
}
