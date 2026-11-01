package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleRecipes
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
        RecipesListener.init()

        val recipeManager = ModuleRecipes
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

        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
        RecipesListener.attachOnlinePlayers(onlinePlayers)
        onlinePlayers.forEach { it.refreshRecipes() }
    }

    fun shutdown() {
        workspaces.values.toSet().forEach { workspace ->
            val player =
                MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull { it.inventory === workspace.inventory }
                    ?: workspace.inventory.viewers.firstOrNull()
            if (player != null) workspace.returnGridItems(player)
        }
        val recipeManager = ModuleRecipes
        recipeBookRecipes.forEach(recipeManager::removeRecipe)
        workspaces.clear()
        recipesByDisplay.clear()
        recipeBookRecipes.clear()
        recipes.clear()
    }
}
