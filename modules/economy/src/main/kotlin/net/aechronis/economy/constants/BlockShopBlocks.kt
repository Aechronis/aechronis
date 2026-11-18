/**
 * Block list and prices for the /blockshop economy (see BlockShop.kt, BlockShopCommand.kt).
 * The list is computed once at class-init, not per request.
 */

package net.aechronis.economy.constants

import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material

// Ores, raw-ore blocks, and other blocks judged an unfair gameplay advantage or purely
// technical/non-buildable -- excluded from PURCHASABLE_BLOCKS regardless of the generic
// suffix filters below. Keep this list reviewable by hand rather than folding it into a
// pattern; every entry here is a deliberate, individually-justified exclusion.
private val BLOCKED_MATERIAL_KEYS: Set<String> =
    setOf(
        "raw_iron_block",
        "raw_gold_block",
        "raw_copper_block",
        "obsidian",
        "crying_obsidian",
        "bedrock",
        "ancient_debris",
        "netherite_block",
        "reinforced_deepslate",
        "command_block",
        "repeating_command_block",
        "chain_command_block",
        "structure_block",
        "structure_void",
        "jigsaw",
        "barrier",
        "light",
        "spawner",
        "trial_spawner",
        "vault",
        "beacon",
        "conduit",
        "dragon_egg",
        "end_portal_frame",
        "end_portal",
        "end_gateway",
        "nether_portal",
        "respawn_anchor",
        "tnt",
        "ender_chest",
        // Has no visible item, so it would show up as an empty slot in the shop.
        "air",
        // Game-test blocks that only exist for Mojang's test framework.
        "test_block",
        "test_instance_block",
        // Smelted/refined ore-storage blocks -- each one vanilla-crafts back into 9 ingots/gems,
        // so leaving these purchasable would let a few coins buy the equivalent
        // of 9 mined ores, completely bypassing the mining economy this shop is meant to sit
        // beside. Same "unfair advantage" reasoning as ores/obsidian/netherite above -- this was
        // the actual gap, not those.
        "coal_block",
        "iron_block",
        "gold_block",
        "diamond_block",
        "emerald_block",
        "lapis_block",
        "redstone_block",
        "copper_block",
        "exposed_copper",
        "weathered_copper",
        "oxidized_copper",
        "waxed_copper_block",
        "waxed_exposed_copper",
        "waxed_weathered_copper",
        "waxed_oxidized_copper",
        // Summons the Wither when 4 placed in a T with soul sand/soil -- the one purchasable
        // material that lets a player skip a real boss-fight gate entirely, not just skip mining.
        "wither_skeleton_skull",
        // Food blocks: the shop sells building blocks, not a way to skip farming.
        "cake",
        "melon",
        "pumpkin",
        "wheat",
    )

private fun isBlockedKey(key: String): Boolean =
    key in BLOCKED_MATERIAL_KEYS ||
        // Infested blocks spawn silverfish when broken; the petrified slab is a legacy block.
        key.startsWith("infested_") ||
        key.startsWith("petrified_") ||
        key.endsWith("_ore") ||
        key.endsWith("_bucket") ||
        // Portable storage: every colour of shulker box, plus the ender chest.
        key.endsWith("shulker_box")

// Thin blocks are cheaper per piece than full blocks.
private val THIN_BLOCK_SUFFIXES = listOf("_fence", "_fence_gate", "_wall", "_pane", "_bars")

/** How many of [material] one coin buys in the block shop: 16 fences, gates, walls, panes and bars, 4 of anything else. */
fun blocksPerCoin(material: Material): Int {
    val key = material.key().asString().substringAfter(':')
    return if (THIN_BLOCK_SUFFIXES.any(key::endsWith)) 16 else 4
}

// Items that are not blocks but are still sold in the shop: dyes (the Dyes folder) so builders can colour
// things, and the item frame (an entity, so it has no Block entry) for decorating.
private val SHOP_ITEM_KEYS: Set<String> =
    listOf(
        "white",
        "orange",
        "magenta",
        "light_blue",
        "yellow",
        "lime",
        "pink",
        "gray",
        "light_gray",
        "cyan",
        "purple",
        "blue",
        "brown",
        "green",
        "red",
        "black",
    ).map { "minecraft:${it}_dye" }.toSet() + "minecraft:item_frame"

/**
 * Every material purchasable from the block shop (priced by blocksPerCoin): every placeable
 * block item (matched against the Block registry, so tools/food/spawn-eggs/etc. are
 * excluded automatically) minus BLOCKED_MATERIAL_KEYS/ores/buckets above. Sorted by
 * namespaced key for stable, alphabetical GUI paging.
 */
val PURCHASABLE_BLOCKS: List<Material> =
    run {
        val blockKeys = Block.values().map { it.key().asString() }.toSet()
        Material
            .values()
            .filter { material -> material.key().asString() in blockKeys || material.key().asString() in SHOP_ITEM_KEYS }
            .filterNot { material -> isBlockedKey(material.key().asString().substringAfter(':')) }
            .sortedBy { material -> material.key().asString() }
    }
