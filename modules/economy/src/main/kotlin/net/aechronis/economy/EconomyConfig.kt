package net.aechronis.economy

import net.minestom.server.item.Material

data class EconomyConfig(
    /** Blocks that pay coins instead of item drops, unless Silk Touch applies. */
    val coinBlocks: Map<Material, Int> = mapOf(Material.STONE to 1),
)
