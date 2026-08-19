package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.BlocksListener
import net.aechronis.vanilla.objects.StonecutterConversionRecipe
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.Material

object Blocks {
    val stonecutters: MutableSet<Inventory> = mutableSetOf()
    val outputsByInput: MutableMap<Material, MutableList<Material>> = mutableMapOf()

    fun openConverter(player: Player) {
        val inv = Inventory(InventoryType.STONE_CUTTER, Component.text("Block Converter"))
        stonecutters.add(inv)
        player.openInventory(inv)
    }

    internal fun conversionOutputs(cycles: List<List<Material>>): Map<Material, List<Material>> {
        val outputsByInput = linkedMapOf<Material, LinkedHashSet<Material>>()

        for (cycle in cycles) {
            val materials = cycle.distinct()
            if (materials.size < 2) continue

            for (input in materials) {
                val outputs = outputsByInput.getOrPut(input) { linkedSetOf() }
                outputs.addAll(materials.filter { it != input })
            }
        }

        return outputsByInput.mapValues { it.value.toList() }
    }

    fun init() {
        val timeStart = System.currentTimeMillis()
        outputsByInput.clear()

        val recipeManager = MinecraftServer.getRecipeManager()
        for ((input, outputs) in conversionOutputs(Vanilla.config.blocksConfig.converterCycles)) {
            outputsByInput[input] = outputs.toMutableList()
            for (output in outputs) {
                recipeManager.addRecipe(StonecutterConversionRecipe(input, output))
            }
        }

        BlocksListener.init()

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Blocks enabled in ${timeLoad}ms")
    }
}
