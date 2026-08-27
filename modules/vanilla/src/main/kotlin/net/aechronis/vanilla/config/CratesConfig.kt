package net.aechronis.vanilla.config

import net.aechronis.vanilla.objects.Crate
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

data class CratesConfig(
    val crates: List<Crate> = listOf(voteCrate()),
)

private fun voteCrate(): Crate =
    Crate(
        id = "vote",
        title = "Vote Crate",
        rewards =
            linkedMapOf(
                ItemStack.of(Material.DIAMOND_BLOCK, 64) to 2.0,
                ItemStack.of(Material.IRON_BLOCK, 64) to 3.0,
                ItemStack.of(Material.GOLD_BLOCK, 64) to 3.0,
                ItemStack.of(Material.DRAGON_BREATH, 32) to 2.0,
                ItemStack.of(Material.DIAMOND, 64) to 10.0,
                ItemStack.of(Material.IRON_INGOT, 64) to 10.0,
                ItemStack.of(Material.GOLD_INGOT, 64) to 10.0,
                ItemStack.of(Material.COOKED_BEEF, 128) to 20.0,
                ItemStack.of(Material.GUNPOWDER, 64) to 10.0,
                ItemStack.of(Material.IRON_INGOT, 32) to 10.0,
                ItemStack.of(Material.GOLD_INGOT, 32) to 10.0,
                ItemStack.of(Material.DIAMOND, 32) to 10.0,
            ),
    )
