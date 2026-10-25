package net.aechronis.koth.listeners

import net.aechronis.koth.Koth
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerRespawnEvent

object KothListener {
    // Vanilla must finish death and combat-logout handling before KOTH reconciles capture state.
    private val eventNode = EventNode.all("koth").setPriority(1)

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
        eventNode.addListener(PlayerMoveEvent::class.java, ::onMove)
        eventNode.addListener(PlayerDeathEvent::class.java, ::onDeath)
        eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
        eventNode.addListener(PlayerRespawnEvent::class.java, ::onRespawn)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }
}
