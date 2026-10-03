package net.aechronis.recipes

import net.aechronis.recipes.craft.Blocks
import net.aechronis.recipes.craft.Food
import net.aechronis.recipes.craft.Ingredients
import net.aechronis.recipes.craft.Smelting
import net.aechronis.recipes.craft.Tools
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.vanilla.VanillaModule

class RecipesModule : AechronisModule {
    override val id = "recipes"
    override val dependencies = setOf("vanilla")
    override val reloadTogether = setOf("vanilla")

    override fun configure(context: ModuleContext) {
        VanillaModule.addRecipes(
            Blocks.list + Tools.list + Smelting.list + Ingredients.list + Food.list,
            Blocks.converterCycles,
        )
    }
}
