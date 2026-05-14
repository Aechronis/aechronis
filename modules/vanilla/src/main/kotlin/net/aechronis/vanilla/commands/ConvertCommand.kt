package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Blocks
import net.kyori.adventure.text.Component
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType

class ConvertCommand : Command("convert", "vanilla.convert") {
    init {
        setDefaultExecutor { player, _ ->
            val inv = Inventory(InventoryType.STONE_CUTTER, Component.text("Block Converter"))
            Blocks.stonecutters.add(inv)
            player.openInventory(inv)
        }
    }
}
