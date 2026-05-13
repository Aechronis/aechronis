package net.aechronis.vanilla.managers

import net.aechronis.vanilla.listeners.RecipesCloseListener
import net.aechronis.vanilla.listeners.RecipesConnectionListener
import net.aechronis.vanilla.listeners.RecipesGridListener
import net.aechronis.vanilla.listeners.RecipesShiftClickListener
import net.aechronis.vanilla.listeners.RecipesTableListener
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Recipes {
    val recipes: MutableList<Recipe> = ArrayList()
    val workspaces: HashMap<AbstractInventory, RecipesWorkspace> = HashMap()

    fun init() {
        // measure load time
        val timeStart = System.currentTimeMillis()
        RecipesCloseListener.init()
        RecipesConnectionListener.init()
        RecipesGridListener.init()
        RecipesShiftClickListener.init()
        RecipesTableListener.init()

        val plank = RecipesIngredient.Companion.of(Material.OAK_PLANKS)!!
        val log = RecipesIngredient.Companion.of(Material.OAK_LOG)!!

        // workbench
        recipes.add(
            Shaped(
                2,
                2,
                arrayOf(plank, plank, plank, plank),
                ItemStack.of(Material.CRAFTING_TABLE),
            ),
        )

        // planks
        recipes.add(
            RecipesShapeless(
                listOf(log),
                ItemStack.of(Material.OAK_PLANKS, 4),
            ),
        )

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Recpies enabled in ${timeLoad}ms")
    }
}
