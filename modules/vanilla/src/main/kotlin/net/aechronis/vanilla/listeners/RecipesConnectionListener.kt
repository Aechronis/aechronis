package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Recipes.recipes
import net.aechronis.vanilla.managers.Recipes.workspaces
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.utils.inventory.PlayerInventoryUtils

object RecipesConnectionListener {
    fun onPlayerQuit(event: PlayerDisconnectEvent) {
        workspaces.remove(event.player.inventory)
    }

    fun onPlayerSpawn(event: PlayerSpawnEvent) {
        if (!event.isFirstSpawn) return
        val player = event.player
        val recipesWorkspace =
            RecipesWorkspace(
                player.inventory,
                PlayerInventoryUtils.CRAFT_RESULT,
                intArrayOf(
                    PlayerInventoryUtils.CRAFT_SLOT_1,
                    PlayerInventoryUtils.CRAFT_SLOT_2,
                    PlayerInventoryUtils.CRAFT_SLOT_3,
                    PlayerInventoryUtils.CRAFT_SLOT_4,
                ),
                2,
                2,
                recipes,
            )
        workspaces[player.inventory] = recipesWorkspace
        recipesWorkspace.refresh()
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, RecipesConnectionListener::onPlayerQuit)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, RecipesConnectionListener::onPlayerSpawn)
    }
}
