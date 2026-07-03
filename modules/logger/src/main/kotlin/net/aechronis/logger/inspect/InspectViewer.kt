package net.aechronis.logger.inspect

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.time.Duration
import java.time.Instant

fun show(
    player: Player,
    x: Int,
    y: Int,
    z: Int,
) {
    Logger.repository
        .lookupAsync(x, y, z, 32)
        .whenComplete { entries, exception ->
            if (exception != null) {
                println("inspect lookup failed: $exception")
                player.sendMessage(Component.text("[Logger] lookup failed", NamedTextColor.RED))
                return@whenComplete
            }
            player.sendMessage(header(x, y, z, entries.size))
            if (entries.isEmpty()) {
                player.sendMessage(Component.text("  no recorded actions", NamedTextColor.GRAY))
            } else {
                entries.forEach { player.sendMessage(line(it)) }
            }
        }
}

private fun header(
    x: Int,
    y: Int,
    z: Int,
    count: Int,
): Component =
    Component
        .text("[Logger] ", NamedTextColor.GOLD)
        .append(Component.text("$x,$y,$z ", NamedTextColor.YELLOW))
        .append(Component.text("($count)", NamedTextColor.GRAY))

private fun line(
    entry: BlockLogEntry,
): Component {
    val ago = formatAgo(Duration.between(Instant.ofEpochMilli(entry.timestamp), Instant.now()))
    val verb =
        when (entry.action) {
            BlockAction.BREAK -> "broke"
            BlockAction.PLACE -> "placed"
            BlockAction.INTERACT -> "used"
        }
    val target =
        when (entry.action) {
            BlockAction.BREAK -> entry.blockOld
            BlockAction.PLACE -> entry.blockNew
            BlockAction.INTERACT -> entry.blockNew
        }
    return Component
        .text("  $ago ago ", NamedTextColor.GRAY)
        .append(Component.text(entry.playerName, NamedTextColor.AQUA))
        .append(Component.text(" $verb ", NamedTextColor.WHITE))
        .append(Component.text(target, NamedTextColor.GREEN))
}

private fun formatAgo(d: Duration): String {
    val s = d.seconds
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        else -> "${s / 86400}d"
    }
}
