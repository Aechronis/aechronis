package net.aechronis.crates

import net.minestom.server.item.ItemStack

/** A crate an iteration ships with. It is created on startup only when no crate with [id] exists. */
data class CrateDefaults(
    val id: String,
    val title: String,
    /** Reward items and their weights; a weight's share of the total is its chance. */
    val rewards: List<Pair<ItemStack, Double>>,
)
