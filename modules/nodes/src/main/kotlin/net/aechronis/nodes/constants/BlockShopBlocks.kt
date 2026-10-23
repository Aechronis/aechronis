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
    "respawn_anchor", "tnt",
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
    key.endsWith("_ore") ||
    key.endsWith("_bucket")

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

/**
 * Every material purchasable from the block shop for 1 block coin each: every placeable
 * block item (matched against the Block registry, so tools/food/spawn-eggs/etc. are
 * excluded automatically) minus BLOCKED_MATERIAL_KEYS/ores/buckets above. Sorted by
 * namespaced key for stable, alphabetical GUI paging.
 */
val PURCHASABLE_BLOCKS: List<Material> = run {
    val blockKeys = Block.values().map { it.key().asString() }.toSet()
    Material.values()
        .filter { material -> material.key().asString() in blockKeys }
        .filterNot { material -> isBlockedKey(material.key().asString().substringAfter(':')) }
        .sortedBy { material -> material.key().asString() }
}

/**
 * Folder grouping for the shop GUI (e.g. every oak/spruce/.../warped block -- planks,
 * stairs, slabs, doors, fences, logs -- lives under WOOD). Keyword-matched against each
 * material's key, first match wins; anything unmatched lands in OTHER so no purchasable
 * block is ever hidden by a miscategorization.
 */
enum class BlockCategory(val label: String, val icon: Material) {
    WOOD("Wood", Material.OAK_LOG),
    NETHER("Nether", Material.NETHERRACK),
    STONE("Stone & Deepslate", Material.STONE),
    SANDSTONE_TERRACOTTA("Sandstone & Terracotta", Material.SANDSTONE),
    CONCRETE_GLASS("Concrete & Glass", Material.WHITE_CONCRETE),
    WOOL_CARPET("Wool & Carpet", Material.WHITE_WOOL),
    COPPER_AMETHYST("Copper & Amethyst", Material.COPPER_BLOCK),
    NATURE("Nature & Plants", Material.OAK_LEAVES),
    FUNCTIONAL("Functional", Material.CHEST),
    OTHER("Other", Material.BRICKS),
}

private val WOOD_SPECIES = listOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "crimson", "warped", "bamboo", "pale_oak")
private val WOOD_GENERIC = setOf("ladder", "scaffolding", "bookshelf", "chiseled_bookshelf")
private val NETHER_KEYWORDS = listOf("nether", "nylium", "soul_sand", "soul_soil", "magma", "glowstone", "shroomlight", "quartz")
private val STONE_KEYWORDS = listOf("stone", "cobble", "andesite", "diorite", "granite", "blackstone", "basalt", "tuff", "calcite", "brick")
private val SANDSTONE_TERRACOTTA_KEYWORDS = listOf("sand", "terracotta", "hardened_clay")
private val CONCRETE_GLASS_KEYWORDS = listOf("concrete", "glass")
private val WOOL_CARPET_KEYWORDS = listOf("wool", "carpet")
private val COPPER_AMETHYST_KEYWORDS = listOf("copper", "amethyst")
private val NATURE_KEYWORDS = listOf(
    "leaves", "flower", "sapling", "mushroom", "moss", "mud", "clay", "ice", "snow", "pumpkin",
    "melon", "coral", "hay_block", "dripstone", "vine", "kelp", "sponge", "sea_pickle", "lily_pad",
)
private val FUNCTIONAL_KEYWORDS = listOf(
    "chest", "barrel", "furnace", "smoker", "door", "trapdoor", "piston", "dispenser", "dropper",
    "hopper", "observer", "repeater", "comparator", "lever", "button", "pressure_plate", "rail",
    "redstone", "lamp", "daylight_detector", "tripwire", "target", "lectern", "anvil", "grindstone",
    "cartography_table", "smithing_table", "stonecutter", "loom", "composter", "beehive", "bee_nest",
    "jukebox", "note_block", "campfire", "lantern", "torch", "candle", "cauldron", "brewing_stand",
    "enchanting_table", "crafting_table", "fletching_table", "cake",
)

private fun classify(key: String): BlockCategory = when {
    WOOD_SPECIES.any { key.contains(it) } || key in WOOD_GENERIC -> BlockCategory.WOOD
    NETHER_KEYWORDS.any { key.contains(it) } -> BlockCategory.NETHER
    STONE_KEYWORDS.any { key.contains(it) } -> BlockCategory.STONE
    SANDSTONE_TERRACOTTA_KEYWORDS.any { key.contains(it) } -> BlockCategory.SANDSTONE_TERRACOTTA
    CONCRETE_GLASS_KEYWORDS.any { key.contains(it) } -> BlockCategory.CONCRETE_GLASS
    WOOL_CARPET_KEYWORDS.any { key.contains(it) } -> BlockCategory.WOOL_CARPET
    COPPER_AMETHYST_KEYWORDS.any { key.contains(it) } -> BlockCategory.COPPER_AMETHYST
    NATURE_KEYWORDS.any { key.contains(it) } -> BlockCategory.NATURE
    FUNCTIONAL_KEYWORDS.any { key.contains(it) } -> BlockCategory.FUNCTIONAL
    else -> BlockCategory.OTHER
}

/** [PURCHASABLE_BLOCKS] grouped into [BlockCategory] folders, computed once. */
val CATEGORY_BLOCKS: Map<BlockCategory, List<Material>> = PURCHASABLE_BLOCKS
    .groupBy { material -> classify(material.key().asString().substringAfter(':')) }
    .let { grouped -> BlockCategory.entries.associateWith { category -> grouped[category].orEmpty() } }
