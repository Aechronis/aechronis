package net.aechronis.recipes.craft

import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Blocks {
    private data class ResourceBlock(
        val resource: Material,
        val block: Material,
    )

    private val logPlanks =
        linkedMapOf(
            Material.OAK_LOG to Material.OAK_PLANKS,
            Material.STRIPPED_OAK_LOG to Material.OAK_PLANKS,
            Material.OAK_WOOD to Material.OAK_PLANKS,
            Material.STRIPPED_OAK_WOOD to Material.OAK_PLANKS,
            Material.SPRUCE_LOG to Material.SPRUCE_PLANKS,
            Material.STRIPPED_SPRUCE_LOG to Material.SPRUCE_PLANKS,
            Material.SPRUCE_WOOD to Material.SPRUCE_PLANKS,
            Material.STRIPPED_SPRUCE_WOOD to Material.SPRUCE_PLANKS,
            Material.BIRCH_LOG to Material.BIRCH_PLANKS,
            Material.STRIPPED_BIRCH_LOG to Material.BIRCH_PLANKS,
            Material.BIRCH_WOOD to Material.BIRCH_PLANKS,
            Material.STRIPPED_BIRCH_WOOD to Material.BIRCH_PLANKS,
            Material.JUNGLE_LOG to Material.JUNGLE_PLANKS,
            Material.STRIPPED_JUNGLE_LOG to Material.JUNGLE_PLANKS,
            Material.JUNGLE_WOOD to Material.JUNGLE_PLANKS,
            Material.STRIPPED_JUNGLE_WOOD to Material.JUNGLE_PLANKS,
            Material.ACACIA_LOG to Material.ACACIA_PLANKS,
            Material.STRIPPED_ACACIA_LOG to Material.ACACIA_PLANKS,
            Material.ACACIA_WOOD to Material.ACACIA_PLANKS,
            Material.STRIPPED_ACACIA_WOOD to Material.ACACIA_PLANKS,
            Material.CHERRY_LOG to Material.CHERRY_PLANKS,
            Material.STRIPPED_CHERRY_LOG to Material.CHERRY_PLANKS,
            Material.CHERRY_WOOD to Material.CHERRY_PLANKS,
            Material.STRIPPED_CHERRY_WOOD to Material.CHERRY_PLANKS,
            Material.DARK_OAK_LOG to Material.DARK_OAK_PLANKS,
            Material.STRIPPED_DARK_OAK_LOG to Material.DARK_OAK_PLANKS,
            Material.DARK_OAK_WOOD to Material.DARK_OAK_PLANKS,
            Material.STRIPPED_DARK_OAK_WOOD to Material.DARK_OAK_PLANKS,
            Material.PALE_OAK_LOG to Material.PALE_OAK_PLANKS,
            Material.STRIPPED_PALE_OAK_LOG to Material.PALE_OAK_PLANKS,
            Material.PALE_OAK_WOOD to Material.PALE_OAK_PLANKS,
            Material.STRIPPED_PALE_OAK_WOOD to Material.PALE_OAK_PLANKS,
            Material.MANGROVE_LOG to Material.MANGROVE_PLANKS,
            Material.STRIPPED_MANGROVE_LOG to Material.MANGROVE_PLANKS,
            Material.MANGROVE_WOOD to Material.MANGROVE_PLANKS,
            Material.STRIPPED_MANGROVE_WOOD to Material.MANGROVE_PLANKS,
            Material.BAMBOO_BLOCK to Material.BAMBOO_PLANKS,
            Material.STRIPPED_BAMBOO_BLOCK to Material.BAMBOO_PLANKS,
            Material.CRIMSON_STEM to Material.CRIMSON_PLANKS,
            Material.STRIPPED_CRIMSON_STEM to Material.CRIMSON_PLANKS,
            Material.CRIMSON_HYPHAE to Material.CRIMSON_PLANKS,
            Material.STRIPPED_CRIMSON_HYPHAE to Material.CRIMSON_PLANKS,
            Material.WARPED_STEM to Material.WARPED_PLANKS,
            Material.STRIPPED_WARPED_STEM to Material.WARPED_PLANKS,
            Material.WARPED_HYPHAE to Material.WARPED_PLANKS,
            Material.STRIPPED_WARPED_HYPHAE to Material.WARPED_PLANKS,
        )

    private val planks = logPlanks.values.toSet()
    private val slabs =
        setOf(
            Material.OAK_SLAB,
            Material.SPRUCE_SLAB,
            Material.BIRCH_SLAB,
            Material.JUNGLE_SLAB,
            Material.ACACIA_SLAB,
            Material.CHERRY_SLAB,
            Material.DARK_OAK_SLAB,
            Material.PALE_OAK_SLAB,
            Material.MANGROVE_SLAB,
            Material.BAMBOO_SLAB,
            Material.CRIMSON_SLAB,
            Material.WARPED_SLAB,
        )

    private val anyPlank = RecipesIngredient.of(planks)!!
    private val anyWoodSlab = RecipesIngredient.of(slabs)!!
    private val cobblestone = RecipesIngredient.of(Material.COBBLESTONE)!!
    private val ironIngot = RecipesIngredient.of(Material.IRON_INGOT)!!
    private val leather = RecipesIngredient.of(Material.LEATHER)!!
    private val stick = RecipesIngredient.of(Material.STICK)!!

    private val cycleBases =
        listOf(
            base("nether wood", Material.CRIMSON_STEM, Material.RED_DYE),
            base("colours", Material.WHITE_WOOL, Material.WHITE_DYE),
            base("plants", Material.OAK_LEAVES, Material.GREEN_DYE),
            base("sandstone", Material.SAND, Material.YELLOW_DYE),
            base("nether building blocks", Material.NETHERRACK, Material.ORANGE_DYE),
            base("blackstone", Material.BLACKSTONE, Material.BLACK_DYE),
            base("quartz", Material.QUARTZ_BLOCK, Material.LIGHT_GRAY_DYE),
            base("end blocks", Material.END_STONE, Material.PURPLE_DYE),
            base("ocean monuments", Material.PRISMARINE, Material.CYAN_DYE),
            base("terracotta", Material.TERRACOTTA, Material.BROWN_DYE),
            base("snow and ice", Material.SNOW, Material.LIGHT_BLUE_DYE),
            base("resin", Material.RESIN_BLOCK, Material.PINK_DYE),
            base("rare biome blocks", Material.OCHRE_FROGLIGHT, Material.LIME_DYE),
            base("coral", Material.TUBE_CORAL_BLOCK, Material.MAGENTA_DYE),
            base("sulfur", Material.SULFUR, Material.GRAY_DYE),
            base("cinnabar", Material.CINNABAR, Material.BLUE_DYE),
            RecipesShapeless(
                listOf(RecipesIngredient.of(Material.COAL)!!, stick),
                ItemStack.of(Material.TORCH),
            ),
        )

    /** Copper is excluded because all copper block variants belong to the copper converter cycle. */
    private val resourceBlocks =
        listOf(
            ResourceBlock(Material.COAL, Material.COAL_BLOCK),
            ResourceBlock(Material.IRON_INGOT, Material.IRON_BLOCK),
            ResourceBlock(Material.GOLD_INGOT, Material.GOLD_BLOCK),
            ResourceBlock(Material.REDSTONE, Material.REDSTONE_BLOCK),
            ResourceBlock(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK),
            ResourceBlock(Material.DIAMOND, Material.DIAMOND_BLOCK),
            ResourceBlock(Material.EMERALD, Material.EMERALD_BLOCK),
            ResourceBlock(Material.QUARTZ, Material.QUARTZ_BLOCK),
            ResourceBlock(Material.RAW_IRON, Material.RAW_IRON_BLOCK),
            ResourceBlock(Material.RAW_GOLD, Material.RAW_GOLD_BLOCK),
            ResourceBlock(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK),
        )

    val list: List<Recipe> =
        buildList {
            for ((log, planks) in logPlanks) {
                add(RecipesShapeless(listOf(RecipesIngredient.of(log)!!), ItemStack.of(planks, 8)))
            }

            add(Shaped(1, 2, arrayOf(anyPlank, anyPlank), ItemStack.of(Material.STICK, 4)))
            add(Shaped(2, 2, arrayOf(anyPlank, anyPlank, anyPlank, anyPlank), ItemStack.of(Material.CRAFTING_TABLE)))
            add(
                Shaped(
                    3,
                    3,
                    arrayOf(
                        stick,
                        stick,
                        stick,
                        stick,
                        null,
                        stick,
                        stick,
                        stick,
                        stick,
                    ),
                    ItemStack.of(Material.ITEM_FRAME),
                ),
            )
            add(
                Shaped(
                    3,
                    3,
                    arrayOf(
                        ironIngot,
                        null,
                        ironIngot,
                        ironIngot,
                        stick,
                        ironIngot,
                        ironIngot,
                        null,
                        ironIngot,
                    ),
                    ItemStack.of(Material.RAIL, 32),
                ),
            )
            addAll(barrelRecipes())
            add(
                Shaped(
                    3,
                    2,
                    arrayOf(
                        null,
                        RecipesIngredient.of(Material.IRON_INGOT)!!,
                        null,
                        cobblestone,
                        cobblestone,
                        cobblestone,
                    ),
                    ItemStack.of(Material.STONECUTTER),
                ),
            )
            add(RecipesShapeless(listOf(RecipesIngredient.of(Material.COAL)!!), ItemStack.of(Material.BLACK_DYE, 8)))
            addAll(compactingRecipes())
            addAll(cycleBases)
        }

    private fun barrelRecipes(): List<Recipe> =
        listOf(
            Shaped(
                3,
                3,
                arrayOf(
                    anyPlank,
                    anyPlank,
                    anyPlank,
                    anyPlank,
                    null,
                    anyPlank,
                    anyPlank,
                    anyPlank,
                    anyPlank,
                ),
                ItemStack.of(Material.BARREL, 2),
            ),
            Shaped(
                3,
                3,
                arrayOf(
                    anyPlank,
                    anyWoodSlab,
                    anyPlank,
                    anyPlank,
                    null,
                    anyPlank,
                    anyPlank,
                    anyWoodSlab,
                    anyPlank,
                ),
                ItemStack.of(Material.BARREL, 2),
            ),
        )

    private fun compactingRecipes(): List<Recipe> =
        resourceBlocks.flatMap { (resource, block) ->
            val resourceIngredient = RecipesIngredient.of(resource)!!
            listOf(
                Shaped(3, 3, Array(9) { resourceIngredient }, ItemStack.of(block)),
                RecipesShapeless(listOf(RecipesIngredient.of(block)!!), ItemStack.of(resource, 9)),
            )
        }

    private fun base(
        cycle: String,
        output: Material,
        dye: Material,
    ): Recipe {
        require(dye.key().value().endsWith("_dye")) { "Base recipe for $cycle requires a dye" }
        return RecipesShapeless(
            listOf(cobblestone, RecipesIngredient.of(dye)!!),
            ItemStack.of(output),
        )
    }
}
