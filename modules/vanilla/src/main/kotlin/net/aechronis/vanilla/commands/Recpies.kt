package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Recipes

class Recpies : Command("recpies", "vanilla.recpies", "recipes") {
    init {
        setDefaultExecutor { player, _ ->
            Recipes.openRecipeBrowser(player)
        }
    }
}
