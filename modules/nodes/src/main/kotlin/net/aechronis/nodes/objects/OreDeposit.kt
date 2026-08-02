/**
 * OreDeposit
 *
 * Represents single material hidden ore
 * drop properties (chance, amount, etc...)
 */

package net.aechronis.nodes.objects

import net.minestom.server.item.Material

data class OreDeposit(
    val material: Material,
    val dropChance: Double,
    val minAmount: Int,
    val maxAmount: Int,
    val ymin: Int = 0,
    val ymax: Int = 255,
) {
    // return new ore deposit from merging two
    // merge rules:
    // - sum together dropChance (going >1.0 is okay)
    // - take MAX of both minAmount and maxAmount
    fun merge(other: OreDeposit): OreDeposit = OreDeposit(
        this.material,
        this.dropChance + other.dropChance,
        this.minAmount.coerceAtLeast(other.minAmount),
        this.maxAmount.coerceAtLeast(other.maxAmount),
        this.ymin,
        this.ymax,
    )
}
