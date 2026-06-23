package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.RecipesListener
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.minestom.server.inventory.AbstractInventory

object Recipes {
    val recipes: MutableList<Recipe> = ArrayList()
    val workspaces: HashMap<AbstractInventory, RecipesWorkspace> = HashMap()

    fun init() {
        // measure load time
        val timeStart = System.currentTimeMillis()
        RecipesListener.init()

        recipes.addAll(Vanilla.config!!.recpies)

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Recpies enabled in ${timeLoad}ms")
    }
}
