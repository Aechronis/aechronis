package net.aechronis.bundles.objects

import net.aechronis.bundles.Bundles
import net.minestom.server.item.ItemStack

data class Bundle(
    val items: Map<Int, ItemStack>,
) {
    fun makeBundle(): ItemStack = Bundles.makeBundle(items)
}
