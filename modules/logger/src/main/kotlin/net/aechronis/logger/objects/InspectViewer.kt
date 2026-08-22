package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.logger.utils.Pages
import net.aechronis.logger.utils.formatAgo
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

fun show(
    player: Player,
    x: Int,
    y: Int,
    z: Int,
) {
    val blockFuture = Logger.repository.lookupAsync(x, y, z, INSPECT_LIMIT)
    val storageFuture: CompletableFuture<List<StorageChange>> =
        player.instance?.let { instance ->
            Logger.storageChange.lookupAsync(VanillaStorage.storageId(instance.uuid, x, y, z), INSPECT_LIMIT)
        } ?: CompletableFuture.completedFuture(emptyList())

    blockFuture
        .thenCombine(storageFuture) { blockEntries, storageEntries ->
            val blockLines = blockEntries.map { InspectLine(it.timestamp, line(it)) }
            val storageLines = storageEntries.map { InspectLine(it.timestamp, storageLine(it)) }
            (blockLines + storageLines)
                .sortedByDescending(InspectLine::timestamp)
                .take(INSPECT_LIMIT)
                .map(InspectLine::component)
        }.whenComplete { lines, exception ->
            if (exception != null) {
                println("inspect lookup failed: $exception")
                player.sendMessage(Component.text("[Logger] lookup failed", NamedTextColor.RED))
                return@whenComplete
            }
            Pages.send(player, "$x,$y,$z", lines)
        }
}

private const val INSPECT_LIMIT = 200

private data class InspectLine(
    val timestamp: Long,
    val component: Component,
)

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
    component = component.append(Component.text(" [${entry.source}/${entry.origin}]", NamedTextColor.DARK_GRAY))
    return component
        .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to ${entry.x},${entry.y},${entry.z}")))
        .clickEvent(ClickEvent.runCommand("/tp ${entry.x} ${entry.y} ${entry.z}"))
}
