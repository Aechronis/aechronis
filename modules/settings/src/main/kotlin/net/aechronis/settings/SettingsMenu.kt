package net.aechronis.settings

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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Chest menu with one item per registered setting: left click for the next value, right click for the previous. */
object SettingsMenu {
    private class Session(
        val inventory: Inventory,
        val settings: List<PlayerSetting>,
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun open(player: Player) {
        val settings = PlayerSettings.all().take(54)
        if (settings.isEmpty()) {
            player.sendMessage(Component.text("No settings available.", NamedTextColor.GRAY))
            return
        }
        val inventory = Inventory(InventoryType.valueOf("CHEST_${(settings.size - 1) / 9 + 1}_ROW"), Component.text("Settings"))
        settings.forEachIndexed { slot, setting -> inventory.setItemStack(slot, item(setting, player)) }
        val session = Session(inventory, settings)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    fun onClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uuid] ?: return
        if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
    }

    fun onClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        if (player.openInventory !== session.inventory) return
        // Cancel every inventory action, including shifts, drags, number keys and drops.
        event.isCancelled = true
        if (event.inventory !== session.inventory) return
        val step =
            when (event.click) {
                is Click.Left -> 1
                is Click.Right -> -1
                else -> return
            }
        val slot = event.slot
        ModuleScheduler.scheduleNextTick {
            if (!player.isOnline || sessions[player.uuid] !== session || player.openInventory !== session.inventory) return@scheduleNextTick
            val setting = session.settings.getOrNull(slot) ?: return@scheduleNextTick
            val values = setting.values
            val next = values[Math.floorMod(values.indexOf(setting.get(player)) + step, values.size)]
            if (!setting.set(player, next)) {
                player.sendMessage(Component.text("${setting.title} cannot be changed right now.", NamedTextColor.RED))
            }
            session.inventory.setItemStack(slot, item(setting, player))
        }
    }

    fun shutdown() {
        sessions.toMap().forEach { (uuid, session) ->
            val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)
            if (player?.openInventory === session.inventory) player.closeInventory()
        }
        sessions.clear()
    }

    private fun item(
        setting: PlayerSetting,
        player: Player,
    ): ItemStack {
        val current = setting.get(player)
        val lore =
            setting.values.map { value ->
                if (value == current) {
                    Component.text("> $value", NamedTextColor.GREEN)
                } else {
                    Component.text("  $value", NamedTextColor.GRAY)
                }.decoration(TextDecoration.ITALIC, false)
            } +
                listOf(
                    Component.empty(),
                    Component
                        .text(
                            "Left click: next | Right click: previous",
                            NamedTextColor.DARK_GRAY,
                        ).decoration(TextDecoration.ITALIC, false),
                )
        return ItemStack
            .of(setting.icon)
            .withCustomName(Component.text(setting.title, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            .withLore(lore)
    }
}
