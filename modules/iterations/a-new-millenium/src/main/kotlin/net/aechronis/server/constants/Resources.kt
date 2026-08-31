package net.aechronis.server.constants

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Resources {
    // Displayed as "Fuel" via the resource pack's lang override (assets/minecraft/lang/en_us.json)
    // rather than a per-stack custom name, so every dried kelp block reads as "Fuel" - including
    // ones a player already holds - not just ones minted through this object.
    val fuel: ItemStack = ItemStack.of(Material.DRIED_KELP_BLOCK)
}
