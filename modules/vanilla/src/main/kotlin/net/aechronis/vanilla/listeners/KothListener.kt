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
        var captureChanged = false
        for (state in Koth.active.values) {
            if (state.capturer == player.uuid) {
                if (!Koth.isInside(state.definition, player, event.newPosition)) {
                    Koth.resetCapture(state)
                    captureChanged = true
                }
            } else if (state.capturer == null && Koth.isInside(state.definition, player, event.newPosition)) {
                Koth.beginCapture(state, player, now)
                captureChanged = true
            }
        }

        if (captureChanged) Koth.updateBossBars(now)
        Koth.updateBossBarsFor(player, event.newPosition, now)
    }

    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        Koth.deadPlayers += player.uuid
        Koth.resetCaptures(player.uuid)
        Koth.updateBossBars(System.currentTimeMillis())
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player
        Koth.deadPlayers -= player.uuid
        Koth.resetCaptures(player.uuid)
        Koth.active.values.forEach { state ->
            state.bossBars.remove(player.uuid)
            state.visibleTo.remove(player.uuid)
        }
        Koth.updateBossBars(System.currentTimeMillis())
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
