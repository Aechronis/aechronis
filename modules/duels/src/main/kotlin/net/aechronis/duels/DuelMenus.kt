package net.aechronis.duels

import net.aechronis.server.hasPermission
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
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

/** Kit picker, then (supporters only) map picker, then the invite goes out. */
object DuelMenus {
    /** Permission for choosing the map; everyone else gets a random one. */
    const val MAP_SELECT_PERMISSION = "duels.map.select"

    private const val RANDOM_SLOT = 22

    private class Session(
        val inventory: Inventory,
        val target: UUID,
        val kit: Kit?,
        val actions: Map<Int, () -> Unit>,
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun register(context: ModuleContext) {
        context.addListener(InventoryPreClickEvent::class.java, ::onClick)
        context.addListener(InventoryCloseEvent::class.java) { event ->
            val session = sessions[event.player.uuid] ?: return@addListener
            if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
        }
    }

    fun closeAll() {
        sessions.keys.toList().forEach { uuid ->
            val session = sessions.remove(uuid) ?: return@forEach
            val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)
            if (player?.openInventory === session.inventory) player.closeInventory()
        }
    }

    fun openKits(
        player: Player,
        target: Player,
    ) {
        val kits = Kits.all()
        if (kits.isEmpty()) {
            player.sendMessage(Component.text("No duel kits are available.", NamedTextColor.RED))
            return
        }
        val inventory = Inventory(InventoryType.CHEST_3_ROW, Component.text("Duel ${target.username}: pick a kit"))
        frame(inventory)
        val actions = HashMap<Int, () -> Unit>()
        kits.take(7).forEachIndexed { i, kit ->
            val slot = 10 + i
            inventory.setItemStack(
                slot,
                kit.icon().withCustomName(label(kit.displayName)).withLore(Component.text("Click to choose", NamedTextColor.GRAY)),
            )
            actions[slot] = { onKitChosen(player, target, kit) }
        }
        show(player, Session(inventory, target.uuid, null, actions))
    }

    private fun onKitChosen(
        player: Player,
        target: Player,
        kit: Kit,
    ) {
        if (!player.hasPermission(MAP_SELECT_PERMISSION)) {
            Invites.send(player, target, kit, Maps.random())
            return player.closeInventory()
        }
        val maps = Maps.all()
        val inventory = Inventory(InventoryType.CHEST_3_ROW, Component.text("Duel ${target.username}: pick a map"))
        frame(inventory)
        val actions = HashMap<Int, () -> Unit>()
        maps.take(7).forEachIndexed { i, map ->
            val slot = 10 + i
            val icon = Material.fromKey("minecraft:${map.icon}") ?: Material.MAP
            inventory.setItemStack(
                slot,
                ItemStack.of(icon).withCustomName(label(map.name)).withLore(Component.text("Click to choose", NamedTextColor.GRAY)),
            )
            actions[slot] = {
                Invites.send(player, target, kit, map)
                player.closeInventory()
            }
        }
        inventory.setItemStack(
            RANDOM_SLOT,
            ItemStack.of(Material.COMPASS).withCustomName(label("Random map")),
        )
        actions[RANDOM_SLOT] = {
            Invites.send(player, target, kit, Maps.random())
            player.closeInventory()
        }
        show(player, Session(inventory, target.uuid, kit, actions))
    }

    private fun show(
        player: Player,
        session: Session,
    ) {
        sessions[player.uuid] = session
        if (!player.openInventory(session.inventory)) sessions.remove(player.uuid, session)
    }

    /** Glass rim: dark panes everywhere, real buttons are placed over them afterwards. */
    private fun frame(inventory: Inventory) {
        val rim = ItemStack.of(Material.BLACK_STAINED_GLASS_PANE).withCustomName(Component.text(" "))
        val accent = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE).withCustomName(Component.text(" "))
        for (slot in 0 until inventory.size) {
            val edge = slot < 9 || slot >= inventory.size - 9 || slot % 9 == 0 || slot % 9 == 8
            inventory.setItemStack(slot, if (edge) rim else accent)
        }
    }

    private fun label(text: String) = Component.text(text, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)

    private fun onClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        if (player.openInventory !== session.inventory) return
        // Cancel every action, including shifts, drags, number keys and drops.
        event.isCancelled = true
        if (event.inventory !== session.inventory || event.click !is Click.Left) return
        val action = session.actions[event.slot] ?: return
        ModuleScheduler.scheduleNextTick {
            if (player.isOnline && sessions[player.uuid] === session && player.openInventory === session.inventory) action()
        }
    }
}
