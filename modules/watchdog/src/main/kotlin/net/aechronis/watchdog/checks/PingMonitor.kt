package net.aechronis.watchdog.checks

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.aechronis.watchdog.objects.PlayerStateReg
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.common.PingPacket

internal object PingMonitor {
    fun checkTimeout(
        player: Player,
        state: PlayerState,
        config: WatchdogConfig,
        flag: FlagSink,
    ) {
        val timedOut =
            state.pendingPings.entries.removeIf { (_, sentTick) ->
                PlayerStateReg.currentTick - sentTick > config.pingTimeoutTicks
            }
        if (timedOut) flag(player, FlagType.TIMEOUT_PING_PACKETS, 1.0, "did not respond to watchdog ping")
    }

    fun send(
        player: Player,
        state: PlayerState,
    ) {
        if (state.pendingPings.size > 4) return
        val id = ++state.pingSequence
        state.pendingPings[id] = PlayerStateReg.currentTick
        player.sendPacket(PingPacket(id))
    }
}
