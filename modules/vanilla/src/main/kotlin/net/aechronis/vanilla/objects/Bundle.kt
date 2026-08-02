package net.aechronis.vanilla.objects

import net.aechronis.vanilla.managers.Bundles
import net.minestom.server.item.ItemStack

data class Bundle(
    val items: Map<Int, ItemStack>,
) {
    fun makeBundle(): ItemStack = Bundles.makeBundle(items)
}
