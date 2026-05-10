package net.aechronis.vanilla.recipes.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.recipes.Recipes.recipes
import net.aechronis.vanilla.recipes.Recipes.workspaces
import net.aechronis.vanilla.recipes.craft.Workspace
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent

object ConnectionListener {
    fun onPlayerQuit(event: PlayerDisconnectEvent) {
        workspaces.remove(event.player.inventory)
    }

    fun onPlayerSpawn(event: PlayerSpawnEvent) {
        if (!event.isFirstSpawn) return
        val player = event.player
        val workspace =
            Workspace(
                player.inventory,
                player,
                0,
                intArrayOf(1, 2, 3, 4),
                2,
                2,
                recipes,
            )
        workspaces[player.inventory] = workspace
        workspace.refresh()
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ConnectionListener::onPlayerQuit)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, ConnectionListener::onPlayerSpawn)
    }
}
