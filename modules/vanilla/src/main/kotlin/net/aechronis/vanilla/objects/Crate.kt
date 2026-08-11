package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

data class Crate(
    val id: String,
    val title: String,
    val material: Material = Material.CHEST_MINECART,
    val rewards: Map<ItemStack, Double> = emptyMap(),
) {
    fun validate() {
        require(id.isNotBlank()) { "Crate id must not be blank" }
        require(title.isNotBlank()) { "Crate '$id' title must not be blank" }
        require(rewards.isNotEmpty()) { "Crate '$id' must have at least one reward" }
        require(rewards.size <= 9) { "Crate '$id' cannot have more than 9 rewards" }
        require(rewards.keys.none { it.isAir }) { "Crate '$id' cannot contain an air reward" }
        require(rewards.values.all { it.isFinite() && it > 0.0 }) {
            "Crate '$id' reward chances must be finite and greater than zero"
        }
    }
}
