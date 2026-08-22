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

fun showStorageLookup(
    player: Player,
    entries: List<StorageChange>,
    summary: String,
) {
    Pages.send(player, summary, entries.map(::storageLine))
}

internal fun storageLine(entry: StorageChange): Component {
    val ago = formatAgo(Duration.between(Instant.ofEpochMilli(entry.timestamp), Instant.now()))
    val actor = entry.playerName ?: "unknown"
    val verb = if (entry.action == StorageChangeAction.DEPOSIT) "deposited" else "withdrew"
    val item =
        entry.item
            .material()
            .key()
            .asString()
    val location = VanillaStorage.parseStorageId(entry.storageId)
    var component =
        Component
            .text("  $ago ago ", NamedTextColor.GRAY)
            .append(Component.text(actor, NamedTextColor.AQUA))
            .append(Component.text(" $verb ${entry.amount} ", NamedTextColor.WHITE))
            .append(Component.text(item, NamedTextColor.GREEN))
    if (location != null) {
        component =
            component.append(
                Component.text(" @ ${location.second},${location.third},${location.fourth}", NamedTextColor.DARK_GRAY),
            )
    } else {
        component = component.append(Component.text(" [${entry.storageId}]", NamedTextColor.DARK_GRAY))
    }
    component = component.append(Component.text(" [${entry.source}/${entry.origin}]", NamedTextColor.DARK_GRAY))

    val slot = entry.slot?.let { "\nSlot: $it" } ?: ""
    component =
        component.hoverEvent(
            HoverEvent.showText(Component.text("Storage: ${entry.storageId}$slot")),
        )
    if (location != null) {
        component =
            component.clickEvent(
                ClickEvent.runCommand("/tp ${location.second} ${location.third} ${location.fourth}"),
            )
    }
    return component
}
