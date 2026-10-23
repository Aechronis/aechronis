/**
 * Shop folders, one per old /convert block chain (Logs & Stems, Wood Blocks, Stone, Copper, ...), so related
 * blocks sit together. A block listed in more than one chain stays in the first one; purchasable blocks that
 * are in no chain land in the final "Other" folder.
 */

package net.aechronis.nodes.constants

import net.minestom.server.item.Material

class BlockFolder(
    val label: String,
    val icon: Material,
    val materials: List<Material>,
)

private fun materialOf(key: String): Material? = Material.fromKey("minecraft:$key")

private class FolderSpec(
    val label: String,
    val icon: String,
    val keys: List<String>,
)

private val FOLDER_SPECS: List<FolderSpec> =
    listOf(
        FolderSpec(
            "Logs & Stems",
            "oak_log",
            listOf(
                "oak_log", "stripped_oak_log", "oak_wood", "stripped_oak_wood", "spruce_log", "stripped_spruce_log",
                "spruce_wood", "stripped_spruce_wood", "birch_log", "stripped_birch_log", "birch_wood", "stripped_birch_wood",
                "jungle_log", "stripped_jungle_log", "jungle_wood", "stripped_jungle_wood", "acacia_log", "stripped_acacia_log",
                "acacia_wood", "stripped_acacia_wood", "cherry_log", "stripped_cherry_log", "cherry_wood", "stripped_cherry_wood",
                "dark_oak_log", "stripped_dark_oak_log", "dark_oak_wood", "stripped_dark_oak_wood", "pale_oak_log", "stripped_pale_oak_log",
                "pale_oak_wood", "stripped_pale_oak_wood", "mangrove_log", "stripped_mangrove_log", "mangrove_wood", "stripped_mangrove_wood",
                "bamboo_block", "stripped_bamboo_block", "crimson_stem", "stripped_crimson_stem", "crimson_hyphae", "stripped_crimson_hyphae",
                "warped_stem", "stripped_warped_stem", "warped_hyphae", "stripped_warped_hyphae",
            ),
        ),
        FolderSpec(
            "Wood Blocks",
            "oak_planks",
            listOf(
                "oak_planks", "oak_stairs", "oak_slab", "oak_fence", "oak_fence_gate", "oak_door",
                "oak_trapdoor", "oak_button", "oak_pressure_plate", "oak_sign", "oak_hanging_sign", "oak_shelf",
                "spruce_planks", "spruce_stairs", "spruce_slab", "spruce_fence", "spruce_fence_gate", "spruce_door",
                "spruce_trapdoor", "spruce_button", "spruce_pressure_plate", "spruce_sign", "spruce_hanging_sign", "spruce_shelf",
                "birch_planks", "birch_stairs", "birch_slab", "birch_fence", "birch_fence_gate", "birch_door",
                "birch_trapdoor", "birch_button", "birch_pressure_plate", "birch_sign", "birch_hanging_sign", "birch_shelf",
                "jungle_planks", "jungle_stairs", "jungle_slab", "jungle_fence", "jungle_fence_gate", "jungle_door",
                "jungle_trapdoor", "jungle_button", "jungle_pressure_plate", "jungle_sign", "jungle_hanging_sign", "jungle_shelf",
                "acacia_planks", "acacia_stairs", "acacia_slab", "acacia_fence", "acacia_fence_gate", "acacia_door",
                "acacia_trapdoor", "acacia_button", "acacia_pressure_plate", "acacia_sign", "acacia_hanging_sign", "acacia_shelf",
                "cherry_planks", "cherry_stairs", "cherry_slab", "cherry_fence", "cherry_fence_gate", "cherry_door",
                "cherry_trapdoor", "cherry_button", "cherry_pressure_plate", "cherry_sign", "cherry_hanging_sign", "cherry_shelf",
                "dark_oak_planks", "dark_oak_stairs", "dark_oak_slab", "dark_oak_fence", "dark_oak_fence_gate", "dark_oak_door",
                "dark_oak_trapdoor", "dark_oak_button", "dark_oak_pressure_plate", "dark_oak_sign", "dark_oak_hanging_sign", "dark_oak_shelf",
                "pale_oak_planks", "pale_oak_stairs", "pale_oak_slab", "pale_oak_fence", "pale_oak_fence_gate", "pale_oak_door",
                "pale_oak_trapdoor", "pale_oak_button", "pale_oak_pressure_plate", "pale_oak_sign", "pale_oak_hanging_sign", "pale_oak_shelf",
                "mangrove_planks", "mangrove_stairs", "mangrove_slab", "mangrove_fence", "mangrove_fence_gate", "mangrove_door",
                "mangrove_trapdoor", "mangrove_button", "mangrove_pressure_plate", "mangrove_sign", "mangrove_hanging_sign", "mangrove_shelf",
                "bamboo_planks", "bamboo_mosaic", "bamboo_stairs", "bamboo_mosaic_stairs", "bamboo_slab", "bamboo_mosaic_slab",
                "bamboo_fence", "bamboo_fence_gate", "bamboo_door", "bamboo_trapdoor", "bamboo_button", "bamboo_pressure_plate",
                "bamboo_sign", "bamboo_hanging_sign", "bamboo_shelf",
            ),
        ),
        FolderSpec(
            "Nether Wood Blocks",
            "crimson_planks",
            listOf(
                "crimson_planks", "crimson_stairs", "crimson_slab", "crimson_fence", "crimson_fence_gate", "crimson_door",
                "crimson_trapdoor", "crimson_button", "crimson_pressure_plate", "crimson_sign", "crimson_hanging_sign", "crimson_shelf",
                "warped_planks", "warped_stairs", "warped_slab", "warped_fence", "warped_fence_gate", "warped_door",
                "warped_trapdoor", "warped_button", "warped_pressure_plate", "warped_sign", "warped_hanging_sign", "warped_shelf",
            ),
        ),
        FolderSpec(
            "Dyes",
            "white_dye",
            listOf(
                "white_dye", "orange_dye", "magenta_dye", "light_blue_dye", "yellow_dye", "lime_dye",
                "pink_dye", "gray_dye", "light_gray_dye", "cyan_dye", "purple_dye", "blue_dye",
                "brown_dye", "green_dye", "red_dye", "black_dye",
            ),
        ),
        FolderSpec(
            "Colored Blocks",
            "white_wool",
            listOf(
                "terracotta", "white_wool", "white_carpet", "white_terracotta", "white_concrete_powder", "white_concrete",
                "white_glazed_terracotta", "white_stained_glass", "white_stained_glass_pane", "white_shulker_box", "white_bed", "white_banner",
                "white_candle", "orange_wool", "orange_carpet", "orange_terracotta", "orange_concrete_powder", "orange_concrete",
                "orange_glazed_terracotta", "orange_stained_glass", "orange_stained_glass_pane", "orange_shulker_box", "orange_bed", "orange_banner",
                "orange_candle", "magenta_wool", "magenta_carpet", "magenta_terracotta", "magenta_concrete_powder", "magenta_concrete",
                "magenta_glazed_terracotta", "magenta_stained_glass", "magenta_stained_glass_pane", "magenta_shulker_box", "magenta_bed", "magenta_banner",
                "magenta_candle", "light_blue_wool", "light_blue_carpet", "light_blue_terracotta", "light_blue_concrete_powder", "light_blue_concrete",
                "light_blue_glazed_terracotta", "light_blue_stained_glass", "light_blue_stained_glass_pane", "light_blue_shulker_box", "light_blue_bed", "light_blue_banner",
                "light_blue_candle", "yellow_wool", "yellow_carpet", "yellow_terracotta", "yellow_concrete_powder", "yellow_concrete",
                "yellow_glazed_terracotta", "yellow_stained_glass", "yellow_stained_glass_pane", "yellow_shulker_box", "yellow_bed", "yellow_banner",
                "yellow_candle", "lime_wool", "lime_carpet", "lime_terracotta", "lime_concrete_powder", "lime_concrete",
                "lime_glazed_terracotta", "lime_stained_glass", "lime_stained_glass_pane", "lime_shulker_box", "lime_bed", "lime_banner",
                "lime_candle", "pink_wool", "pink_carpet", "pink_terracotta", "pink_concrete_powder", "pink_concrete",
                "pink_glazed_terracotta", "pink_stained_glass", "pink_stained_glass_pane", "pink_shulker_box", "pink_bed", "pink_banner",
                "pink_candle", "gray_wool", "gray_carpet", "gray_terracotta", "gray_concrete_powder", "gray_concrete",
                "gray_glazed_terracotta", "gray_stained_glass", "gray_stained_glass_pane", "gray_shulker_box", "gray_bed", "gray_banner",
                "gray_candle", "light_gray_wool", "light_gray_carpet", "light_gray_terracotta", "light_gray_concrete_powder", "light_gray_concrete",
                "light_gray_glazed_terracotta", "light_gray_stained_glass", "light_gray_stained_glass_pane", "light_gray_shulker_box", "light_gray_bed", "light_gray_banner",
                "light_gray_candle", "cyan_wool", "cyan_carpet", "cyan_terracotta", "cyan_concrete_powder", "cyan_concrete",
                "cyan_glazed_terracotta", "cyan_stained_glass", "cyan_stained_glass_pane", "cyan_shulker_box", "cyan_bed", "cyan_banner",
                "cyan_candle", "purple_wool", "purple_carpet", "purple_terracotta", "purple_concrete_powder", "purple_concrete",
                "purple_glazed_terracotta", "purple_stained_glass", "purple_stained_glass_pane", "purple_shulker_box", "purple_bed", "purple_banner",
                "purple_candle", "blue_wool", "blue_carpet", "blue_terracotta", "blue_concrete_powder", "blue_concrete",
                "blue_glazed_terracotta", "blue_stained_glass", "blue_stained_glass_pane", "blue_shulker_box", "blue_bed", "blue_banner",
                "blue_candle", "brown_wool", "brown_carpet", "brown_terracotta", "brown_concrete_powder", "brown_concrete",
                "brown_glazed_terracotta", "brown_stained_glass", "brown_stained_glass_pane", "brown_shulker_box", "brown_bed", "brown_banner",
                "brown_candle", "green_wool", "green_carpet", "green_terracotta", "green_concrete_powder", "green_concrete",
                "green_glazed_terracotta", "green_stained_glass", "green_stained_glass_pane", "green_shulker_box", "green_bed", "green_banner",
                "green_candle", "red_wool", "red_carpet", "red_terracotta", "red_concrete_powder", "red_concrete",
                "red_glazed_terracotta", "red_stained_glass", "red_stained_glass_pane", "red_shulker_box", "red_bed", "red_banner",
                "red_candle", "black_wool", "black_carpet", "black_terracotta", "black_concrete_powder", "black_concrete",
                "black_glazed_terracotta", "black_stained_glass", "black_stained_glass_pane", "black_shulker_box", "black_bed", "black_banner",
                "black_candle",
            ),
        ),
        FolderSpec(
            "Plants & Foliage",
            "oak_leaves",
            listOf(
                "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "cherry_leaves",
                "dark_oak_leaves", "pale_oak_leaves", "mangrove_leaves", "azalea_leaves", "flowering_azalea_leaves", "short_grass",
                "fern", "dead_bush", "bush", "short_dry_grass", "tall_dry_grass", "seagrass",
                "dandelion", "golden_dandelion", "torchflower", "poppy", "blue_orchid", "allium",
                "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy",
                "cornflower", "wither_rose", "lily_of_the_valley", "brown_mushroom", "red_mushroom", "brown_mushroom_block",
                "red_mushroom_block", "mushroom_stem", "cactus", "cactus_flower", "sugar_cane", "vine",
                "glow_lichen", "spore_blossom", "big_dripleaf", "small_dripleaf", "hanging_roots", "lily_pad",
                "kelp", "dried_kelp_block", "crimson_roots", "warped_roots", "nether_sprouts", "twisting_vines",
                "weeping_vines", "azalea", "flowering_azalea", "pink_petals", "open_eyeblossom", "closed_eyeblossom",
                "pitcher_plant", "sunflower", "lilac", "rose_bush", "peony", "tall_grass",
                "large_fern", "bamboo",
            ),
        ),
        FolderSpec(
            "Stone",
            "cobblestone",
            listOf(
                "stone", "cobblestone", "granite", "polished_granite", "diorite", "polished_diorite",
                "andesite", "polished_andesite", "stone_stairs", "stone_slab", "smooth_stone", "smooth_stone_slab",
                "cobblestone_stairs", "cobblestone_slab", "cobblestone_wall", "iron_bars", "mossy_cobblestone", "mossy_cobblestone_stairs",
                "mossy_cobblestone_wall", "mossy_cobblestone_slab", "granite_stairs", "granite_slab", "granite_wall", "polished_granite_stairs",
                "polished_granite_slab", "diorite_stairs", "diorite_slab", "diorite_wall", "polished_diorite_stairs", "polished_diorite_slab",
                "andesite_stairs", "andesite_slab", "andesite_wall", "polished_andesite_stairs", "polished_andesite_slab", "brick_stairs",
                "brick_slab", "brick_wall", "stone_bricks", "stone_brick_stairs", "stone_brick_slab", "stone_brick_wall",
                "mossy_stone_bricks", "mossy_stone_brick_stairs", "mossy_stone_brick_slab", "mossy_stone_brick_wall", "cracked_stone_bricks", "chiseled_stone_bricks",
                "bricks",
            ),
        ),
        FolderSpec(
            "Dirt & Moss",
            "dirt",
            listOf(
                "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium",
                "dirt_path", "mud", "packed_mud", "mud_bricks", "mud_brick_stairs", "mud_brick_slab",
                "mud_brick_wall", "moss_block", "moss_carpet", "pale_moss_block", "pale_moss_carpet", "pale_hanging_moss",
            ),
        ),
        FolderSpec(
            "Sand & Sandstone",
            "sand",
            listOf(
                "sand", "red_sand", "gravel", "sandstone", "chiseled_sandstone", "cut_sandstone",
                "smooth_sandstone", "sandstone_stairs", "sandstone_slab", "sandstone_wall", "cut_sandstone_slab", "smooth_sandstone_stairs",
                "red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone", "smooth_red_sandstone", "red_sandstone_stairs", "red_sandstone_slab",
                "red_sandstone_wall", "cut_red_sandstone_slab", "smooth_red_sandstone_stairs",
            ),
        ),
        FolderSpec(
            "Glass",
            "glass",
            listOf(
                "glass", "glass_pane", "tinted_glass", "white_stained_glass", "white_stained_glass_pane", "orange_stained_glass",
                "orange_stained_glass_pane", "magenta_stained_glass", "magenta_stained_glass_pane", "light_blue_stained_glass", "light_blue_stained_glass_pane", "yellow_stained_glass",
                "yellow_stained_glass_pane", "lime_stained_glass", "lime_stained_glass_pane", "pink_stained_glass", "pink_stained_glass_pane", "gray_stained_glass",
                "gray_stained_glass_pane", "light_gray_stained_glass", "light_gray_stained_glass_pane", "cyan_stained_glass", "cyan_stained_glass_pane", "purple_stained_glass",
                "purple_stained_glass_pane", "blue_stained_glass", "blue_stained_glass_pane", "brown_stained_glass", "brown_stained_glass_pane", "green_stained_glass",
                "green_stained_glass_pane", "red_stained_glass", "red_stained_glass_pane", "black_stained_glass", "black_stained_glass_pane",
            ),
        ),
        FolderSpec(
            "Deepslate",
            "deepslate",
            listOf(
                "deepslate", "cobbled_deepslate", "polished_deepslate", "deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles",
                "cracked_deepslate_tiles", "chiseled_deepslate", "cobbled_deepslate_stairs", "cobbled_deepslate_slab", "cobbled_deepslate_wall", "polished_deepslate_stairs",
                "polished_deepslate_slab", "polished_deepslate_wall", "deepslate_brick_stairs", "deepslate_brick_slab", "deepslate_brick_wall", "deepslate_tile_stairs",
                "deepslate_tile_slab", "deepslate_tile_wall",
            ),
        ),
        FolderSpec(
            "Tuff",
            "tuff",
            listOf(
                "tuff", "polished_tuff", "chiseled_tuff", "tuff_bricks", "chiseled_tuff_bricks", "tuff_stairs",
                "tuff_slab", "tuff_wall", "polished_tuff_stairs", "polished_tuff_slab", "polished_tuff_wall", "tuff_brick_stairs",
                "tuff_brick_slab", "tuff_brick_wall",
            ),
        ),
        FolderSpec(
            "Nether Terrain",
            "netherrack",
            listOf(
                "netherrack", "soul_sand", "soul_soil", "basalt", "smooth_basalt", "polished_basalt",
                "magma_block", "nether_bricks", "cracked_nether_bricks", "chiseled_nether_bricks", "nether_brick_stairs", "nether_brick_slab",
                "nether_brick_wall", "nether_brick_fence", "red_nether_bricks", "red_nether_brick_stairs", "red_nether_brick_slab", "red_nether_brick_wall",
                "nether_wart_block", "warped_wart_block", "glowstone", "shroomlight",
            ),
        ),
        FolderSpec(
            "Blackstone",
            "blackstone",
            listOf(
                "blackstone", "polished_blackstone", "polished_blackstone_bricks", "cracked_polished_blackstone_bricks", "chiseled_polished_blackstone", "gilded_blackstone",
                "blackstone_stairs", "blackstone_slab", "blackstone_wall", "polished_blackstone_stairs", "polished_blackstone_slab", "polished_blackstone_wall",
                "polished_blackstone_brick_stairs", "polished_blackstone_brick_slab", "polished_blackstone_brick_wall",
            ),
        ),
        FolderSpec(
            "Quartz",
            "quartz_block",
            listOf(
                "quartz_block",
                "chiseled_quartz_block",
                "quartz_pillar",
                "quartz_bricks",
                "smooth_quartz",
                "quartz_stairs",
                "quartz_slab",
                "smooth_quartz_stairs",
            ),
        ),
        FolderSpec(
            "End Stone",
            "end_stone",
            listOf(
                "end_stone", "end_stone_bricks", "end_stone_brick_stairs", "end_stone_brick_slab", "end_stone_brick_wall", "purpur_block",
                "purpur_pillar", "purpur_stairs", "purpur_slab", "chorus_plant", "chorus_flower",
            ),
        ),
        FolderSpec(
            "Prismarine",
            "prismarine",
            listOf(
                "prismarine", "prismarine_bricks", "dark_prismarine", "prismarine_stairs", "prismarine_brick_stairs", "dark_prismarine_stairs",
                "prismarine_slab", "prismarine_brick_slab", "dark_prismarine_slab", "prismarine_wall", "sea_lantern",
            ),
        ),
        FolderSpec(
            "Clay & Crystals",
            "clay",
            listOf(
                "terracotta", "clay", "calcite", "dripstone_block", "pointed_dripstone", "amethyst_block",
                "amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud",
            ),
        ),
        FolderSpec(
            "Copper",
            "copper_block",
            listOf(
                "copper_block", "exposed_copper", "weathered_copper", "oxidized_copper", "waxed_copper_block", "waxed_exposed_copper",
                "waxed_weathered_copper", "waxed_oxidized_copper", "cut_copper", "exposed_cut_copper", "weathered_cut_copper", "oxidized_cut_copper",
                "waxed_cut_copper", "waxed_exposed_cut_copper", "waxed_weathered_cut_copper", "waxed_oxidized_cut_copper", "chiseled_copper", "exposed_chiseled_copper",
                "weathered_chiseled_copper", "oxidized_chiseled_copper", "waxed_chiseled_copper", "waxed_exposed_chiseled_copper", "waxed_weathered_chiseled_copper", "waxed_oxidized_chiseled_copper",
                "cut_copper_stairs", "exposed_cut_copper_stairs", "weathered_cut_copper_stairs", "oxidized_cut_copper_stairs", "waxed_cut_copper_stairs", "waxed_exposed_cut_copper_stairs",
                "waxed_weathered_cut_copper_stairs", "waxed_oxidized_cut_copper_stairs", "cut_copper_slab", "exposed_cut_copper_slab", "weathered_cut_copper_slab", "oxidized_cut_copper_slab",
                "waxed_cut_copper_slab", "waxed_exposed_cut_copper_slab", "waxed_weathered_cut_copper_slab", "waxed_oxidized_cut_copper_slab", "copper_door", "exposed_copper_door",
                "weathered_copper_door", "oxidized_copper_door", "waxed_copper_door", "waxed_exposed_copper_door", "waxed_weathered_copper_door", "waxed_oxidized_copper_door",
                "copper_trapdoor", "exposed_copper_trapdoor", "weathered_copper_trapdoor", "oxidized_copper_trapdoor", "waxed_copper_trapdoor", "waxed_exposed_copper_trapdoor",
                "waxed_weathered_copper_trapdoor", "waxed_oxidized_copper_trapdoor", "copper_grate", "exposed_copper_grate", "weathered_copper_grate", "oxidized_copper_grate",
                "waxed_copper_grate", "waxed_exposed_copper_grate", "waxed_weathered_copper_grate", "waxed_oxidized_copper_grate", "copper_chain", "exposed_copper_chain",
                "weathered_copper_chain", "oxidized_copper_chain", "waxed_copper_chain", "waxed_exposed_copper_chain", "waxed_weathered_copper_chain", "waxed_oxidized_copper_chain",
                "copper_bars", "exposed_copper_bars", "weathered_copper_bars", "oxidized_copper_bars", "waxed_copper_bars", "waxed_exposed_copper_bars",
                "waxed_weathered_copper_bars", "waxed_oxidized_copper_bars", "copper_lantern", "exposed_copper_lantern", "weathered_copper_lantern", "oxidized_copper_lantern",
                "waxed_copper_lantern", "waxed_exposed_copper_lantern", "waxed_weathered_copper_lantern", "waxed_oxidized_copper_lantern", "copper_chest", "exposed_copper_chest",
                "weathered_copper_chest", "oxidized_copper_chest", "waxed_copper_chest", "waxed_exposed_copper_chest", "waxed_weathered_copper_chest", "waxed_oxidized_copper_chest",
                "copper_golem_statue", "exposed_copper_golem_statue", "weathered_copper_golem_statue", "oxidized_copper_golem_statue", "waxed_copper_golem_statue", "waxed_exposed_copper_golem_statue",
                "waxed_weathered_copper_golem_statue", "waxed_oxidized_copper_golem_statue", "lightning_rod", "exposed_lightning_rod", "weathered_lightning_rod", "oxidized_lightning_rod",
                "waxed_lightning_rod", "waxed_exposed_lightning_rod", "waxed_weathered_lightning_rod", "waxed_oxidized_lightning_rod",
            ),
        ),
        FolderSpec(
            "Snow & Ice",
            "snow",
            listOf(
                "snow",
                "snow_block",
                "ice",
                "packed_ice",
                "blue_ice",
            ),
        ),
        FolderSpec(
            "Resin",
            "resin_block",
            listOf(
                "resin_block",
                "resin_bricks",
                "chiseled_resin_bricks",
                "resin_brick_stairs",
                "resin_brick_slab",
                "resin_brick_wall",
            ),
        ),
        FolderSpec(
            "Lanterns & Metalwork",
            "lantern",
            listOf(
                "lantern", "soul_lantern", "iron_chain", "scaffolding", "flower_pot", "decorated_pot",
                "bookshelf", "chiseled_bookshelf", "jukebox", "tnt", "hay_block", "bone_block",
                "honey_block", "honeycomb_block", "slime_block", "beehive", "bee_nest", "sponge",
                "wet_sponge", "crafting_table", "furnace", "blast_furnace", "smoker", "barrel",
                "chest", "trapped_chest", "ender_chest", "enchanting_table", "brewing_stand", "cauldron",
                "anvil", "chipped_anvil", "damaged_anvil", "bell", "loom", "cartography_table",
                "fletching_table", "grindstone", "lectern", "smithing_table", "stonecutter", "composter",
                "beacon", "lodestone", "iron_door", "iron_trapdoor", "candle",
            ),
        ),
        FolderSpec(
            "Campfires & Lights",
            "campfire",
            listOf(
                "campfire",
                "soul_campfire",
                "end_rod",
                "torch",
                "soul_torch",
                "copper_torch",
                "sea_pickle",
                "conduit",
            ),
        ),
        FolderSpec(
            "Froglights",
            "ochre_froglight",
            listOf(
                "ochre_froglight", "verdant_froglight", "pearlescent_froglight", "dried_ghast", "creaking_heart", "heavy_core",
                "wildflowers", "leaf_litter", "firefly_bush", "sulfur_spike",
            ),
        ),
        FolderSpec(
            "Coral",
            "tube_coral_block",
            listOf(
                "tube_coral_block", "brain_coral_block", "bubble_coral_block", "fire_coral_block", "horn_coral_block", "dead_tube_coral_block",
                "dead_brain_coral_block", "dead_bubble_coral_block", "dead_fire_coral_block", "dead_horn_coral_block", "tube_coral", "brain_coral",
                "bubble_coral", "fire_coral", "horn_coral", "dead_tube_coral", "dead_brain_coral", "dead_bubble_coral",
                "dead_fire_coral", "dead_horn_coral", "tube_coral_fan", "brain_coral_fan", "bubble_coral_fan", "fire_coral_fan",
                "horn_coral_fan",
            ),
        ),
        FolderSpec(
            "Sulfur",
            "sulfur",
            listOf(
                "sulfur", "polished_sulfur", "sulfur_bricks", "chiseled_sulfur", "sulfur_stairs", "sulfur_slab",
                "sulfur_wall", "polished_sulfur_stairs", "polished_sulfur_slab", "polished_sulfur_wall", "sulfur_brick_stairs", "sulfur_brick_slab",
                "sulfur_brick_wall",
            ),
        ),
        FolderSpec(
            "Cinnabar",
            "cinnabar",
            listOf(
                "cinnabar", "polished_cinnabar", "cinnabar_bricks", "cinnabar_stairs", "cinnabar_slab", "cinnabar_wall",
                "polished_cinnabar_stairs", "polished_cinnabar_slab", "polished_cinnabar_wall", "cinnabar_brick_stairs", "cinnabar_brick_slab", "cinnabar_brick_wall",
            ),
        ),
    )

/** The folders shown at the top of /blockshop, in order, each limited to blocks that are actually for sale. */
val BLOCK_FOLDERS: List<BlockFolder> =
    run {
        val purchasable = PURCHASABLE_BLOCKS.toSet()
        val claimed = mutableSetOf<Material>()
        val folders = mutableListOf<BlockFolder>()
        for (spec in FOLDER_SPECS) {
            val materials = spec.keys.mapNotNull(::materialOf).filter { it in purchasable && claimed.add(it) }
            val icon = materialOf(spec.icon)?.takeIf { it in purchasable } ?: materials.firstOrNull()
            if (materials.isNotEmpty() && icon != null) folders += BlockFolder(spec.label, icon, materials)
        }
        val rest = PURCHASABLE_BLOCKS.filter { it !in claimed }
        if (rest.isNotEmpty()) folders += BlockFolder("Other Blocks", Material.BRICKS, rest)
        folders
    }
