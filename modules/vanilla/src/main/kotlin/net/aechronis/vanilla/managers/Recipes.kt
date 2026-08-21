package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.RecipesListener
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipeBookRecipe
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.minestom.server.MinecraftServer
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.recipe.Recipe as MinestomRecipe

object Recipes {
    val recipes: MutableList<Recipe> = ArrayList()
    val workspaces: HashMap<AbstractInventory, RecipesWorkspace> = HashMap()
    private val recipeBookRecipes: MutableList<MinestomRecipe> = ArrayList()

    fun init() {
        // measure load time
        val timeStart = System.currentTimeMillis()
        RecipesListener.init()

        val recipeManager = MinecraftServer.getRecipeManager()
        recipeBookRecipes.forEach(recipeManager::removeRecipe)
        recipeBookRecipes.clear()

        recipes.clear()
        recipes.addAll(Vanilla.config.recipesConfig.recpies)
        recipes.mapTo(recipeBookRecipes, ::RecipeBookRecipe)
        recipeBookRecipes.forEach(recipeManager::addRecipe)

        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.refreshRecipes() }

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Recpies enabled in ${timeLoad}ms")
    }
}
