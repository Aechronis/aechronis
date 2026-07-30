package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Koth
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerRespawnEvent

object KothListener {
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val now = System.currentTimeMillis()
        for (state in Koth.active.values) {
            if (state.capturer == player.uuid) {
                if (!Koth.isInside(state.config, player, event.newPosition)) Koth.resetCapture(state)
            } else if (state.capturer == null && Koth.isInside(state.config, player, event.newPosition)) {
                Koth.beginCapture(state, player.uuid, now)
            }
        }
        Koth.updateBossBars(now)
    }

    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        Koth.deadPlayers += player.uuid
        Koth.resetCaptures(player.uuid)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player
        Koth.deadPlayers -= player.uuid
        Koth.resetCaptures(player.uuid)
        Koth.active.values.forEach { state ->
            state.bossBars.remove(player.uuid)
            state.visibleTo.remove(player.uuid)
        }
    }

    fun onRespawn(event: PlayerRespawnEvent) {
        Koth.deadPlayers -= event.player.uuid
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerMoveEvent::class.java, ::onMove)
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, ::onDeath)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
        Vanilla.eventNode.addListener(PlayerRespawnEvent::class.java, ::onRespawn)
    }
}
