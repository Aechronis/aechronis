package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Commands
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent

object CommandsListener {
    fun onDisconnect(event: PlayerDisconnectEvent) {
        if (!Vanilla.config.playerDataEnabled) {
            Commands.closeEnderChest(event.player)
            Commands.removeEnderChest(event.player)
        }
    }

    fun onSpawn(event: PlayerSpawnEvent) {
        Commands.allowEnderChest(event.player)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, CommandsListener::onDisconnect)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, CommandsListener::onSpawn)
    }
}
