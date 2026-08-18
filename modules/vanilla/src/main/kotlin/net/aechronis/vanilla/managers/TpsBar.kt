package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.server.ServerTickMonitorEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.min

object TpsBar {
    private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

    private val bars = ConcurrentHashMap<UUID, BossBar>()
    private val runtime = Runtime.getRuntime()
    private val maxMemory = runtime.maxMemory() / BYTES_PER_MEBIBYTE

    fun init() {
        Vanilla.eventNode.addListener(ServerTickMonitorEvent::class.java) { event ->
            val tickTime = floor(event.tickMonitor.tickTime * 100.0) / 100.0
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MEBIBYTE
            val name = Component.text("MSPT: $tickTime | Mem: ${usedMemory}MB/${maxMemory}MB")
            val progress = min(tickTime / MinecraftServer.TICK_MS, 1.0).toFloat()
            val color = if (tickTime > MinecraftServer.TICK_MS) BossBar.Color.RED else BossBar.Color.GREEN

            val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers.associateBy { it.uuid }
            bars.keys.removeIf { it !in onlinePlayers }
            bars.values.forEach { bar ->
                bar.name(name)
                bar.progress(progress)
                bar.color(color)
            }
        }
    }

    fun toggle(player: Player): Boolean {
        val oldBar = bars.remove(player.uuid)
        if (oldBar != null) {
            player.hideBossBar(oldBar)
            return false
        }

        val bar = BossBar.bossBar(Component.text("MSPT: -- | Mem: --"), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)
        bars[player.uuid] = bar
        player.showBossBar(bar)
        return true
    }

    internal fun isEnabled(player: Player): Boolean = bars.containsKey(player.uuid)
}
