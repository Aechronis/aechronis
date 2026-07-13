package net.aechronis.aechronis.tasks

import net.aechronis.aechronis.Aechronis
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.monitoring.TickMonitor
import net.minestom.server.timer.TaskSchedule
import net.minestom.server.utils.MathUtils
import java.util.concurrent.atomic.AtomicReference

object TabManager {
    private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

    private val runtime = Runtime.getRuntime()
    private val maxMemory = runtime.maxMemory() / BYTES_PER_MEBIBYTE
    private val header =
        Component
            .newline()
            .append(Component.text("\ue002").appendNewline()) // server logo
            .appendNewline()
            .appendNewline()
            .appendNewline()
            .appendNewline()
            .appendNewline()
            .append(Component.text("Iteration name goes here", NamedTextColor.GRAY))
            .appendNewline()
            .append(Component.text("                                      ")) // force tab width

    fun start() {
        val lastTick = AtomicReference<TickMonitor>()
        Aechronis.eventNode.addListener(ServerTickMonitorEvent::class.java) { event -> lastTick.set(event.tickMonitor) }

        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                val tickTime = lastTick.get()?.tickTime ?: 0.0
                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MEBIBYTE
                val footer = createFooter(tickTime, usedMemory)

                Audiences.players().sendPlayerListHeaderAndFooter(header, footer)
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }

    private fun createFooter(
        tickTime: Double,
        usedMemory: Long,
    ): Component =
        Component
            .newline()
            .append(Component.text("MSPT: ", NamedTextColor.GOLD))
            .append(Component.text("${MathUtils.round(tickTime, 1)} ms / 50ms", NamedTextColor.GRAY))
            .appendNewline()
            .append(Component.text("Memory: ", NamedTextColor.GOLD))
            .append(Component.text("$usedMemory MB / $maxMemory MB", NamedTextColor.GRAY))
            .appendNewline()
}
