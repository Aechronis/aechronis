package net.aechronis.server.craft

import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment

object Tools {
    private data class ToolSet(
        val pickaxe: Material,
        val axe: Material,
        val shovel: Material,
        val hoe: Material,
    )

    private val woodPlanks =
        setOf(
            Material.OAK_PLANKS,
            Material.SPRUCE_PLANKS,
            Material.BIRCH_PLANKS,
            Material.JUNGLE_PLANKS,
            Material.ACACIA_PLANKS,
            Material.CHERRY_PLANKS,
            Material.DARK_OAK_PLANKS,
            Material.PALE_OAK_PLANKS,
            Material.MANGROVE_PLANKS,
            Material.BAMBOO_PLANKS,
            Material.CRIMSON_PLANKS,
            Material.WARPED_PLANKS,
        )

    private val woodenTools =
        ToolSet(
            Material.WOODEN_PICKAXE,
            Material.WOODEN_AXE,
            Material.WOODEN_SHOVEL,
            Material.WOODEN_HOE,
        )

    private val stoneTools =
        ToolSet(
            Material.STONE_PICKAXE,
            Material.STONE_AXE,
            Material.STONE_SHOVEL,
            Material.STONE_HOE,
        )

    private val diamondTools =
        ToolSet(
            Material.DIAMOND_PICKAXE,
            Material.DIAMOND_AXE,
            Material.DIAMOND_SHOVEL,
            Material.DIAMOND_HOE,
        )

    private val memeEnchantments =
        EnchantmentList.EMPTY
            .with(Enchantment.EFFICIENCY, 5)
            .with(Enchantment.SILK_TOUCH, 1)
            .with(Enchantment.UNBREAKING, 3)

    val list: List<Recipe> =
        buildList {
            addAll(tools(woodPlanks, woodenTools))
            addAll(tools(setOf(Material.COBBLESTONE), stoneTools))
            addAll(tools(setOf(Material.IRON_INGOT), diamondTools))
            addAll(tools(setOf(Material.DIAMOND_BLOCK), diamondTools, memeEnchantments))
            add(
                Shaped(
                    3,
                    2,
                    arrayOf(
                        RecipesIngredient.of(Material.IRON_INGOT)!!,
                        RecipesIngredient.of(Material.IRON_INGOT)!!,
                        RecipesIngredient.of(Material.IRON_INGOT)!!,
                        null,
                        RecipesIngredient.of(Material.REDSTONE)!!,
                        null,
                    ),
                    ItemStack.of(Material.BUNDLE),
                ),
            )
        }

    private fun tools(
        materials: Set<Material>,
        toolSet: ToolSet,
        enchantments: EnchantmentList? = null,
    ): List<Recipe> {
        val ingredient = RecipesIngredient.of(materials)!!
        val stick = RecipesIngredient.of(Material.STICK)!!

        fun output(tool: Material): ItemStack =
            ItemStack.of(tool).let { item ->
                if (enchantments == null) item else item.with(DataComponents.ENCHANTMENTS, enchantments)
            }

        return listOf(
            Shaped(
                3,
                3,
                arrayOf(
                    ingredient,
                    ingredient,
                    ingredient,
                    null,
                    stick,
                    null,
                    null,
                    stick,
                    null,
                ),
                output(toolSet.pickaxe),
            ),
            Shaped(
                2,
                3,
                arrayOf(
                    ingredient,
                    ingredient,
                    ingredient,
                    stick,
                    null,
                    stick,
                ),
                output(toolSet.axe),
            ),
            Shaped(
                1,
                3,
                arrayOf(
                    ingredient,
                    stick,
                    stick,
                ),
                output(toolSet.shovel),
            ),
            Shaped(
                2,
                3,
                arrayOf(
                    ingredient,
                    ingredient,
                    null,
                    stick,
                    null,
                    stick,
                ),
                output(toolSet.hoe),
            ),
        )
    }
}
