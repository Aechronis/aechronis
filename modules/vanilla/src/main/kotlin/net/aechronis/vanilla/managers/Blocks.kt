package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.BlocksListener
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Blocks {
    internal const val INPUT_SLOT = 4
    internal const val OUTPUT_START_SLOT = 9
    internal const val OUTPUT_END_SLOT = 44
    internal const val PREVIOUS_PAGE_SLOT = 45
    private const val PAGE_SLOT = 49
    internal const val NEXT_PAGE_SLOT = 53
    private const val OUTPUTS_PER_PAGE = OUTPUT_END_SLOT - OUTPUT_START_SLOT + 1

    val stonecutters: MutableSet<Inventory> = mutableSetOf()
    val outputsByInput: MutableMap<Material, MutableList<Material>> = mutableMapOf()
    private val converterPages: MutableMap<Inventory, Int> = mutableMapOf()

    fun openConverter(player: Player) {
        val inv = Inventory(InventoryType.CHEST_6_ROW, Component.text("Block Converter"))
        stonecutters.add(inv)
        converterPages[inv] = 0
        refreshConverter(inv)
        player.openInventory(inv)
    }

    internal fun refreshConverter(
        inv: Inventory,
        resetPage: Boolean = false,
    ) {
        if (resetPage) converterPages[inv] = 0

        val input = inv.getItemStack(INPUT_SLOT)
        val outputs = if (input.isAir) emptyList() else outputsByInput[input.material()].orEmpty()
        val pageCount = maxOf(1, (outputs.size + OUTPUTS_PER_PAGE - 1) / OUTPUTS_PER_PAGE)
        val page = (converterPages[inv] ?: 0).coerceIn(0, pageCount - 1)
        converterPages[inv] = page

        for (slot in OUTPUT_START_SLOT..OUTPUT_END_SLOT) {
            val output = outputs.getOrNull(page * OUTPUTS_PER_PAGE + slot - OUTPUT_START_SLOT)
            inv.setItemStack(slot, output?.let { ItemStack.of(it) } ?: ItemStack.AIR)
        }

        inv.setItemStack(
            PREVIOUS_PAGE_SLOT,
            if (page > 0) ItemStack.of(Material.ARROW).withCustomName(Component.text("Previous page")) else ItemStack.AIR,
        )
        inv.setItemStack(
            PAGE_SLOT,
            if (outputs.isNotEmpty()) {
                ItemStack.of(Material.PAPER).withCustomName(Component.text("Page ${page + 1}/$pageCount"))
            } else {
                ItemStack.AIR
            },
        )
        inv.setItemStack(
            NEXT_PAGE_SLOT,
            if (page < pageCount - 1) ItemStack.of(Material.ARROW).withCustomName(Component.text("Next page")) else ItemStack.AIR,
        )
    }

    internal fun currentConverterPage(inv: Inventory): Int = converterPages[inv] ?: 0

    internal fun setConverterPage(
        inv: Inventory,
        page: Int,
    ) {
        converterPages[inv] = page
        refreshConverter(inv)
    }

    internal fun outputFor(
        inv: Inventory,
        slot: Int,
    ): Material? {
        if (slot !in OUTPUT_START_SLOT..OUTPUT_END_SLOT) return null

        val input = inv.getItemStack(INPUT_SLOT)
        if (input.isAir) return null
        val outputs = outputsByInput[input.material()] ?: return null
        val page = converterPages[inv] ?: return null
        return outputs.getOrNull(page * OUTPUTS_PER_PAGE + slot - OUTPUT_START_SLOT)
    }

    internal fun convert(
        player: Player,
        inv: Inventory,
        output: Material,
    ) {
        val input = inv.getItemStack(INPUT_SLOT)
        if (input.isAir || output !in outputsByInput[input.material()].orEmpty()) return

        inv.setItemStack(INPUT_SLOT, ItemStack.AIR)
        var remaining = input.amount()
        val maxStack = ItemStack.of(output).maxStackSize()
        while (remaining > 0) {
            val amount = minOf(remaining, maxStack)
            val stack = ItemStack.of(output, amount)
            if (!player.inventory.addItemStack(stack)) player.dropItem(stack)
            remaining -= amount
        }
    }

    internal fun closeConverter(inv: Inventory) {
        stonecutters.remove(inv)
        converterPages.remove(inv)
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

        for ((input, outputs) in conversionOutputs(Vanilla.config.blocksConfig.converterCycles)) {
            outputsByInput[input] = outputs.toMutableList()
        }

        BlocksListener.init()

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Blocks enabled in ${timeLoad}ms")
    }
}
