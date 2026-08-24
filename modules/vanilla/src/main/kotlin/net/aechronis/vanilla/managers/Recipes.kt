package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.RecipesListener
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipeBookRecipe
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.minestom.server.MinecraftServer
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.recipe.display.RecipeDisplay

object Recipes {
    val recipes: MutableList<Recipe> = ArrayList()
    val workspaces: HashMap<AbstractInventory, RecipesWorkspace> = HashMap()
    private val recipeBookRecipes: MutableList<RecipeBookRecipe> = ArrayList()
    private val recipesByDisplay: MutableMap<RecipeDisplay, Recipe> = HashMap()

    /** Resolves a client-selected recipe-book display to its server-side recipe. */
    fun recipeForDisplay(display: RecipeDisplay): Recipe? = recipesByDisplay[display]

    fun init() {
        // measure load time
        val timeStart = System.currentTimeMillis()
        RecipesListener.init()

        val recipeManager = MinecraftServer.getRecipeManager()
        recipeBookRecipes.forEach(recipeManager::removeRecipe)
        recipeBookRecipes.clear()
        recipesByDisplay.clear()

        recipes.clear()
        recipes.addAll(Vanilla.config.recipesConfig.recpies)
        recipes.mapTo(recipeBookRecipes, ::RecipeBookRecipe)
        for (recipeBookRecipe in recipeBookRecipes) {
            for (display in recipeBookRecipe.displays) {
                check(recipesByDisplay.put(display, recipeBookRecipe.source) == null) {
                    "Duplicate recipe-book display for ${recipeBookRecipe.source}"
                }
            }
            recipeManager.addRecipe(recipeBookRecipe)
        }

        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.refreshRecipes() }

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Recpies enabled in ${timeLoad}ms")
    }
}
