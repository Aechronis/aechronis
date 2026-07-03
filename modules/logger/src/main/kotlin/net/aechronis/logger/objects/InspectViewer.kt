package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.logger.utils.Pages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
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
        .lookupAsync(x, y, z, 200)
        .whenComplete { entries, exception ->
            if (exception != null) {
                println("inspect lookup failed: $exception")
                player.sendMessage(Component.text("[Logger] lookup failed", NamedTextColor.RED))
                return@whenComplete
            }
            Pages.send(player, "$x,$y,$z", entries.map { line(it) })
        }
}

fun showLookup(
    player: Player,
    entries: List<BlockLogEntry>,
    summary: String,
) {
    Pages.send(player, summary, entries.map { line(it, withCoords = true) })
}

private fun line(
    entry: BlockLogEntry,
    withCoords: Boolean = false,
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
    var component =
        Component
            .text("  $ago ago ", NamedTextColor.GRAY)
            .append(Component.text(entry.playerName, NamedTextColor.AQUA))
            .append(Component.text(" $verb ", NamedTextColor.WHITE))
            .append(Component.text(target, NamedTextColor.GREEN))
    if (withCoords) {
        component = component.append(Component.text(" @ ${entry.x},${entry.y},${entry.z}", NamedTextColor.DARK_GRAY))
    }
    return component
        .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to ${entry.x},${entry.y},${entry.z}")))
        .clickEvent(ClickEvent.runCommand("/tp ${entry.x} ${entry.y} ${entry.z}"))
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
