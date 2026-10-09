package net.aechronis.server.config

import net.aechronis.server.craft.HorseRecipes
import net.aechronis.vanilla.VanillaConfig
import net.aechronis.vanilla.config.RecipesConfig

/** Retained configuration contains only Vanilla, Minestom and standard-library value objects. */
internal object VictorianVanillaConfiguration {
    fun create(): VanillaConfig =
        VanillaConfig(
            shopEnabled = false,
            horsesEnabled = true,
            recipesConfig = RecipesConfig(recpies = HorseRecipes.list),
        )
}
