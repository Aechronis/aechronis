/**
 * Block lists for the /sell and /blockshop economy (see BlockShop.kt, SellCommand.kt,
 * BlockShopCommand.kt). Both are computed once at class-init, not per request.
 */

package net.aechronis.nodes.constants

import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material

// Ores, raw-ore blocks, and other blocks judged an unfair gameplay advantage or purely
// technical/non-buildable -- excluded from PURCHASABLE_BLOCKS regardless of the generic
// suffix filters below. Keep this list reviewable by hand rather than folding it into a
// pattern; every entry here is a deliberate, individually-justified exclusion.
private val BLOCKED_MATERIAL_KEYS: Set<String> = setOf(
    "raw_iron_block", "raw_gold_block", "raw_copper_block",
    "obsidian", "crying_obsidian", "bedrock", "ancient_debris", "netherite_block", "reinforced_deepslate",
    "command_block", "repeating_command_block", "chain_command_block",
    "structure_block", "structure_void", "jigsaw", "barrier", "light",
    "spawner", "trial_spawner", "vault",
    "beacon", "conduit", "dragon_egg",
    "end_portal_frame", "end_portal", "end_gateway", "nether_portal",
    "respawn_anchor", "tnt", "ender_chest",
    // Has no visible item, so it would show up as an empty slot in the shop.
    "air",
    // Game-test blocks that only exist for Mojang's test framework.
    "test_block", "test_instance_block",
    // Smelted/refined ore-storage blocks -- each one vanilla-crafts back into 9 ingots/gems,
    // so leaving these purchasable would let 1 dirt-block's worth of coin buy the equivalent
    // of 9 mined ores, completely bypassing the mining economy this shop is meant to sit
    // beside. Same "unfair advantage" reasoning as ores/obsidian/netherite above -- this was
    // the actual gap, not those.
    "coal_block", "iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block",
    "copper_block", "exposed_copper", "weathered_copper", "oxidized_copper",
    "waxed_copper_block", "waxed_exposed_copper", "waxed_weathered_copper", "waxed_oxidized_copper",
    // Summons the Wither when 4 placed in a T with soul sand/soil -- the one purchasable
    // material that lets a player skip a real boss-fight gate entirely, not just skip mining.
    "wither_skeleton_skull",
)

private fun isBlockedKey(key: String): Boolean = key in BLOCKED_MATERIAL_KEYS ||
    // Infested blocks spawn silverfish when broken; the petrified slab is a legacy block.
    key.startsWith("infested_") ||
    key.startsWith("petrified_") ||
    key.endsWith("_ore") ||
    key.endsWith("_bucket") ||
    // Portable storage: every colour of shulker box, plus the ender chest.
    key.endsWith("shulker_box")

/** Every material a player can sell to the block shop for 1 block coin each. */
val SELLABLE_BLOCKS: Set<Material> = buildSet {
    add(Material.COBBLESTONE)
    add(Material.DIRT)
    Material.values().forEach { material ->
        val key = material.key().asString().substringAfter(':')
        if (key.endsWith("_log") || key.endsWith("_wood") || key.endsWith("_stem") || key.endsWith("_hyphae")) {
            add(material)
        }
    }
}

// Dyes are items, not blocks, but they are part of the shop (the Dyes folder) so builders can colour things.
private val SHOP_DYE_KEYS: Set<String> = listOf(
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
).map { "minecraft:${it}_dye" }.toSet()

/**
 * Every material purchasable from the block shop for 1 block coin each: every placeable
 * block item (matched against the Block registry, so tools/food/spawn-eggs/etc. are
 * excluded automatically) minus BLOCKED_MATERIAL_KEYS/ores/buckets above. Sorted by
 * namespaced key for stable, alphabetical GUI paging.
 */
val PURCHASABLE_BLOCKS: List<Material> = run {
    val blockKeys = Block.values().map { it.key().asString() }.toSet()
    Material.values()
        .filter { material -> material.key().asString() in blockKeys || material.key().asString() in SHOP_DYE_KEYS }
        .filterNot { material -> isBlockedKey(material.key().asString().substringAfter(':')) }
        .sortedBy { material -> material.key().asString() }
}
