package net.aechronis.watchdog.runtime

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.checks.AuraCheck
import net.aechronis.watchdog.checks.ClickCheck
import net.aechronis.watchdog.checks.FlagSink
import net.aechronis.watchdog.checks.PingMonitor
import net.aechronis.watchdog.checks.VelocityCheck
import net.aechronis.watchdog.objects.PlayerState
import net.aechronis.watchdog.objects.PlayerStateReg
import net.aechronis.watchdog.probe.TranslationProbe
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player

internal class WatchdogTicker(
    private val config: WatchdogConfig,
    private val state: (Player) -> PlayerState,
    private val isBypassed: (Player) -> Boolean,
    private val probes: TranslationProbe,
    private val flag: FlagSink,
) {
    fun tick() {
        PlayerStateReg.currentTick++
        probes.advance()
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            if (isBypassed(player)) continue
            val playerState = state(player)
            PingMonitor.checkTimeout(player, playerState, config, flag)
            VelocityCheck.check(player, playerState, flag)
            ClickCheck.check(player, playerState, config, flag)
            AuraCheck.check(player, playerState, flag)
            if (PlayerStateReg.currentTick % config.pingIntervalTicks == 0L) {
                PingMonitor.send(player, playerState)
            }
        }
    }
}
