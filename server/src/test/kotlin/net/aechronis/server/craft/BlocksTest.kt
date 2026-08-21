package net.aechronis.server.craft

import net.aechronis.vanilla.objects.RecipesGrid
import net.aechronis.vanilla.objects.RecipesGridSlot
import net.aechronis.vanilla.objects.RecipesShapeless
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlocksTest {
    @Test
    fun `every log variant crafts into four matching planks`() {
        val recipes =
            mapOf(
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

        for ((log, planks) in recipes) {
            val result = craft(log)
            assertEquals(planks, result?.material())
            assertEquals(4, result?.amount())
        }
    }

    @Test
    fun `barrels craft from eight planks or the standard plank and slab recipe`() {
        assertEquals(
            Material.BARREL,
            craft(
                Material.OAK_PLANKS,
                Material.SPRUCE_PLANKS,
                Material.BIRCH_PLANKS,
                Material.JUNGLE_PLANKS,
                null,
                Material.ACACIA_PLANKS,
                Material.CHERRY_PLANKS,
                Material.DARK_OAK_PLANKS,
                Material.PALE_OAK_PLANKS,
            )?.material(),
        )
        assertEquals(
            Material.BARREL,
            craft(
                Material.CRIMSON_PLANKS,
                Material.WARPED_SLAB,
                Material.BAMBOO_PLANKS,
                Material.MANGROVE_PLANKS,
                null,
                Material.CHERRY_PLANKS,
                Material.DARK_OAK_PLANKS,
                Material.BAMBOO_SLAB,
                Material.PALE_OAK_PLANKS,
            )?.material(),
        )
    }

    @Test
    fun `crafting table and stonecutter have bootstrap recipes`() {
        assertEquals(
            Material.CRAFTING_TABLE,
            craft(
                Material.SPRUCE_PLANKS,
                Material.SPRUCE_PLANKS,
                null,
                Material.SPRUCE_PLANKS,
                Material.SPRUCE_PLANKS,
            )?.material(),
        )
        assertEquals(
            Material.STONECUTTER,
            craft(
                null,
                Material.IRON_INGOT,
                null,
                Material.COBBLESTONE,
                Material.COBBLESTONE,
                Material.COBBLESTONE,
            )?.material(),
        )
    }

    @Test
    fun `each biome gated converter cycle has a cobblestone and dye entry`() {
        val baseOutputs =
            setOf(
                Material.CRIMSON_STEM,
                Material.WHITE_WOOL,
                Material.OAK_LEAVES,
                Material.SAND,
                Material.NETHERRACK,
                Material.BLACKSTONE,
                Material.QUARTZ_BLOCK,
                Material.END_STONE,
                Material.PRISMARINE,
                Material.TERRACOTTA,
                Material.SNOW,
                Material.RESIN_BLOCK,
                Material.OCHRE_FROGLIGHT,
                Material.TUBE_CORAL_BLOCK,
                Material.SULFUR,
                Material.CINNABAR,
            )
        val shapeless = Blocks.list.filterIsInstance<RecipesShapeless>()

        for (output in baseOutputs) {
            val recipe = shapeless.single { it.output.material() == output }
            assertEquals(2, recipe.recipesIngredients.size)
            assertTrue(recipe.recipesIngredients.any { it.materials == setOf(Material.COBBLESTONE) })
            assertTrue(
                recipe.recipesIngredients.any { ingredient ->
                    ingredient.materials
                        .single()
                        .key()
                        .value()
                        .endsWith("_dye")
                },
            )
        }
    }

    @Test
    fun `coal crafts into eight black dye while the torch recipe remains`() {
        val dye = craft(Material.COAL)
        assertEquals(Material.BLACK_DYE, dye?.material())
        assertEquals(8, dye?.amount())
        assertEquals(Material.TORCH, craft(Material.COAL, Material.STICK)?.material())
    }

    @Test
    fun `ore resources compact into their blocks and back excluding copper`() {
        val resources =
            mapOf(
                Material.COAL to Material.COAL_BLOCK,
                Material.IRON_INGOT to Material.IRON_BLOCK,
                Material.GOLD_INGOT to Material.GOLD_BLOCK,
                Material.REDSTONE to Material.REDSTONE_BLOCK,
                Material.LAPIS_LAZULI to Material.LAPIS_BLOCK,
                Material.DIAMOND to Material.DIAMOND_BLOCK,
                Material.EMERALD to Material.EMERALD_BLOCK,
                Material.QUARTZ to Material.QUARTZ_BLOCK,
                Material.RAW_IRON to Material.RAW_IRON_BLOCK,
                Material.RAW_GOLD to Material.RAW_GOLD_BLOCK,
                Material.NETHERITE_INGOT to Material.NETHERITE_BLOCK,
            )

        for ((resource, block) in resources) {
            assertEquals(block, craft(*Array(9) { resource })?.material())
            val unpacked = craft(block)
            assertEquals(resource, unpacked?.material())
            assertEquals(9, unpacked?.amount())
        }

        assertNull(craft(*Array(9) { Material.COPPER_INGOT }))
        assertNull(craft(Material.COPPER_BLOCK))
    }

    private fun craft(vararg materials: Material?): ItemStack? {
        val grid =
            RecipesGrid(
                3,
                3,
                Array(9) { slot ->
                    RecipesGridSlot(slot, materials.getOrNull(slot)?.let(ItemStack::of) ?: ItemStack.AIR)
                },
            )
        return Blocks.list.firstNotNullOfOrNull { it.match(grid) }?.result
    }
}
