package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.BlocksListener
import net.aechronis.vanilla.objects.BlockType
import net.aechronis.vanilla.objects.StonecutterConversionRecipe
import net.minestom.server.MinecraftServer
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.Material

object Blocks {
    val variants: MutableMap<BlockType, MutableList<Material>> =
        mutableMapOf(
            BlockType.Stone to mutableListOf(),
            BlockType.Wood to mutableListOf(),
            BlockType.Grass to mutableListOf(),
            BlockType.Dyes to mutableListOf(),
            BlockType.Plants to mutableListOf(),
        )

    val stonecutters: MutableSet<Inventory> = mutableSetOf()
    val outputsByInput: MutableMap<Material, MutableList<Material>> = mutableMapOf()

    fun init() {
        val timeStart = System.currentTimeMillis()
        variants[BlockType.Stone]!!.addAll(
            Vanilla.config!!.blocksStoneType,
        )

        variants[BlockType.Wood]!!.addAll(
            Vanilla.config!!.blocksWoodType,
        )

        variants[BlockType.Grass]!!.addAll(
            Vanilla.config!!.blocksGrassType,
        )

        val rm = MinecraftServer.getRecipeManager()
        for ((_, list) in variants) {
            for (input in list) {
                val outputs = outputsByInput.getOrPut(input) { mutableListOf() }
                for (output in list) {
                    if (input == output) continue
                    rm.addRecipe(StonecutterConversionRecipe(input, output))
                    outputs.add(output)
                }
            }
        }

        BlocksListener.init()

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Blocks enabled in ${timeLoad}ms")
    }
}
