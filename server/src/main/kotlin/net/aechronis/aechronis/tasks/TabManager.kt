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
    fun start() {
        val lastTick = AtomicReference<TickMonitor>()
        Aechronis.eventNode.addListener(ServerTickMonitorEvent::class.java, { event -> lastTick.set(event.getTickMonitor()) })

        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                val runtime = Runtime.getRuntime()
                val tickTime = lastTick.get()?.tickTime ?: 0.0
                val ramUsage = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val maxMemory = runtime.maxMemory() / 1024 / 1024

                val header: Component =
                    Component
                        .newline()
                        .append(Component.text("\ue002").appendNewline()) // server logo
                        .appendNewline()
                        .appendNewline()
                        .appendNewline()
                        .appendNewline()
                        .appendNewline()
                        .append(Component.text("Iteration name goes here").color(NamedTextColor.GRAY))
                        .appendNewline()
                        .append(Component.text("                                      ")) // force tab width

                val footer =
                    Component
                        .newline()
                        .append(
                            Component
                                .text(
                                    "MSPT: ",
                                    NamedTextColor.GOLD,
                                ).append(
                                    Component.text("${MathUtils.round(tickTime, 1)} ms / 50ms", NamedTextColor.GRAY),
                                ).append(Component.newline()),
                        ).append(
                            Component
                                .text(
                                    "Memory: ",
                                    NamedTextColor.GOLD,
                                ).append(Component.text("$ramUsage MB / $maxMemory MB", NamedTextColor.GRAY))
                                .append(Component.newline()),
                        )

                Audiences.players().sendPlayerListHeaderAndFooter(header, footer)
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }
}
