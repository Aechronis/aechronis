package net.aechronis.logger.objects

import net.aechronis.logger.utils.Pages
import net.aechronis.logger.utils.formatAgo
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.time.Duration
import java.time.Instant

fun showFeatureLookup(
    player: Player,
    entries: List<FeatureLogEntry>,
    summary: String,
) {
    Pages.send(player, summary, entries.map { line(it) })
}

private fun line(entry: FeatureLogEntry): Component {
    val ago = formatAgo(Duration.between(Instant.ofEpochMilli(entry.timestamp), Instant.now()))
    var component = Component.text("  $ago ago ", NamedTextColor.GRAY)
    if (entry.playerName != null) {
        component = component.append(Component.text(entry.playerName, NamedTextColor.AQUA))
    }
    component =
        component
            .append(Component.text(" ${entry.summary} ", NamedTextColor.WHITE))
            .append(Component.text("[${entry.source}/${entry.action}]", NamedTextColor.DARK_GRAY))

    val x = entry.x
    val y = entry.y
    val z = entry.z
    if (x != null && y != null && z != null) {
        component =
            component
                .append(Component.text(" @ $x,$y,$z", NamedTextColor.DARK_GRAY))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to $x,$y,$z")))
                .clickEvent(ClickEvent.runCommand("/tp $x $y $z"))
    }

    return component
}
