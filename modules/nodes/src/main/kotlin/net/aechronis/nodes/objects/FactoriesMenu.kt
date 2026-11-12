package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule

private const val MAX_SLOTS = 54

private class FactoriesInventory(type: InventoryType) : Inventory(type, Component.text("Active Factories", NamedTextColor.DARK_GREEN))

/** `/factories`: read-only chest of every producing factory server-wide, soonest first, ticking down once a second. */
object FactoriesMenu {
    fun init() {
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java) { event ->
            // also covers shift-clicks and drags from the player's own inventory while the menu is open
            if (event.player.openInventory is FactoriesInventory) event.isCancelled = true
        }
    }

    fun open(player: Player) {
        val initial = running()
        if (initial.isEmpty()) {
            Message.print(player, "No factories are producing right now")
            return
        }
        // ponytail: chest size is fixed at open, so factories that start later past that size wait for a reopen
        val inventory = FactoriesInventory(inventoryTypeFor(initial.size))

        // re-list every second so factories that start or finish while the menu is open show up or drop off
        fun render() {
            val now = System.currentTimeMillis()
            val shown = running().take(inventory.size)
            for (slot in 0 until inventory.size) {
                inventory.setItemStack(slot, shown.getOrNull(slot)?.let { factoryItem(it, now) } ?: ItemStack.AIR)
            }
        }
        render()
        if (!player.openInventory(inventory)) return
        lateinit var task: Task
        task = ModuleScheduler.buildTask { if (inventory.viewers.isEmpty()) task.cancel() else render() }
            .delay(TaskSchedule.tick(20)).repeat(TaskSchedule.tick(20)).schedule()
    }

    // ponytail: no paging, factories past 54 are cut off; add pages if a server ever runs that many at once
    private fun running(): List<ActiveBuilding> = Building.all()
        .filterIsInstance<ActiveBuilding>()
        // production can finish on the tick thread mid-sort, so read it once per building
        .mapNotNull { building -> building.production?.let { building to it.endsAt } }
        .sortedBy { it.second }
        .take(MAX_SLOTS)
        .map { it.first }

    private fun factoryItem(building: ActiveBuilding, now: Long): ItemStack {
        val production = building.production
            ?: return ItemStack.of(Material.BARRIER).withCustomName(plain("Finished", NamedTextColor.GRAY))
        val town = Territory.fromCoord(Coord(building.chunkX, building.chunkZ))?.town
        val pos = building.position
        return production.output.first().withAmount(1)
            .withCustomName(plain(building.producingName() ?: production.recipeName, NamedTextColor.GOLD))
            .withLore(
                building.displayName.colorIfAbsent(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                plain("Town: ${town?.name ?: "None"}", NamedTextColor.GRAY),
                plain("Nation: ${town?.nation?.name ?: "None"}", NamedTextColor.GRAY),
                plain("${pos.blockX()}, ${pos.blockY()}, ${pos.blockZ()}", NamedTextColor.GRAY),
                plain(timeLeft(production.endsAt - now), NamedTextColor.YELLOW),
            )
    }

    private fun plain(text: String, color: NamedTextColor) = Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun timeLeft(ms: Long): String {
        if (ms <= 0) return "Finishing"
        val seconds = ms / 1000
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return if (hours > 0) "${hours}h ${minutes}m left" else "${minutes}m ${seconds % 60}s left"
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
