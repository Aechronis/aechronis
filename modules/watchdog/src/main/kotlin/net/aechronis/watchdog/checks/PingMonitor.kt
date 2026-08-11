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
        if (!timedOut) return

        if (PlayerStateReg.currentTick - state.lastPingTimeoutTick > TIMEOUT_STRIKE_WINDOW_TICKS) {
            state.pingTimeoutStrikes = 0
        }
        state.lastPingTimeoutTick = PlayerStateReg.currentTick
        state.pingTimeoutStrikes++
        if (state.pingTimeoutStrikes >= REQUIRED_TIMEOUT_STRIKES) {
            flag(
                player,
                FlagType.TIMEOUT_PING_PACKETS,
                0.35,
                "missed ${state.pingTimeoutStrikes} watchdog pong checks",
            )
            state.pingTimeoutStrikes = 0
        }
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

    private const val REQUIRED_TIMEOUT_STRIKES = 3
    private const val TIMEOUT_STRIKE_WINDOW_TICKS = 400L
}
