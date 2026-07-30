package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack

sealed interface KothReward {
    data class Command(
        val command: String,
    ) : KothReward

    data class Item(
        val itemStack: ItemStack,
    ) : KothReward
}
