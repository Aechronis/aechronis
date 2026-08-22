package net.aechronis.logger.objects

import net.aechronis.logger.utils.Pages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

fun showRollbackPreview(
    player: Player,
    plan: RollbackPlan,
    token: String?,
) {
    val lines = mutableListOf<Component>()
    plan.blockChanges.forEach { lines += blockChangeLine(it) }
    plan.storageChanges.forEach { change ->
        lines +=
            Component.text(
                "  storage ${change.storageId}: ${change.targetAction.value} ${change.amount} ${change.item.material().key().asString()}",
                NamedTextColor.GRAY,
            )
    }
    plan.inventoryChanges.forEach { change ->
        lines +=
            Component.text(
                "  inventory ${change.playerUuid} slot ${change.slot} -> ${change.targetItem.material().key().asString()}",
                NamedTextColor.GRAY,
            )
    }
    plan.entityChanges.forEach { change ->
        lines +=
            Component.text(
                "  entity ${change.entityUuid}: ${change.targetAction.value} ${change.entityType}",
                NamedTextColor.GRAY,
            )
    }
    if (plan.skippedBlockCount > 0) {
        lines +=
            Component.text(
                "  * ${plan.skippedBlockCount} historical entries skipped: no recorded instance/state",
                NamedTextColor.DARK_GRAY,
            )
    }

    val targetTime = timeFormat.format(Instant.ofEpochMilli(plan.targetTs))
    val title = "${plan.kind.value.replaceFirstChar(Char::uppercase)} preview: ${plan.totalChangeCount} changes since $targetTime"

    Pages.send(player, title, lines)

    if (token != null) {
        player.sendMessage(
            Component
                .text("[Confirm ${plan.kind.value.replaceFirstChar(Char::uppercase)}]", NamedTextColor.RED)
                .hoverEvent(HoverEvent.showText(Component.text("This will mutate the world. Click to apply.")))
                .clickEvent(ClickEvent.runCommand("/logger ${plan.kind.value} confirm:$token"))
                .append(Component.text("  "))
                .append(
                    Component
                        .text("[Cancel]", NamedTextColor.GRAY)
                        .hoverEvent(HoverEvent.showText(Component.text("Discard this preview without applying it.")))
                        .clickEvent(ClickEvent.runCommand("/logger ${plan.kind.value} cancel:$token")),
                ),
        )
    }
}

fun showChunkRestorePreview(
    player: Player,
    plan: RollbackPlan,
    token: String,
    centerChunkX: Int,
    centerChunkZ: Int,
    radius: Int,
) {
    val lines =
        plan.blockChanges
            .take(CHUNK_PREVIEW_LIMIT)
            .map(::blockChangeLine)
            .toMutableList()
    val omitted = plan.blockChanges.size - lines.size
    if (omitted > 0) {
        lines += Component.text("  ...and $omitted more block changes", NamedTextColor.DARK_GRAY)
    }
    val title =
        if (radius == 0) {
            "Restore original chunk $centerChunkX,$centerChunkZ: ${plan.totalChangeCount} changes"
        } else {
            val chunkCount = (radius * 2 + 1) * (radius * 2 + 1)
            "Restore $chunkCount original chunks around $centerChunkX,$centerChunkZ: ${plan.totalChangeCount} changes"
        }
    Pages.send(player, title, lines)
    player.sendMessage(
        Component
            .text("[Confirm Chunk Restore]", NamedTextColor.RED)
            .hoverEvent(HoverEvent.showText(Component.text("Replace the selected chunks with blocks from logger/original.")))
            .clickEvent(ClickEvent.runCommand("/logger restorechunk confirm:$token"))
            .append(Component.text("  "))
            .append(
                Component
                    .text("[Cancel]", NamedTextColor.GRAY)
                    .hoverEvent(HoverEvent.showText(Component.text("Discard this preview without applying it.")))
                    .clickEvent(ClickEvent.runCommand("/logger restorechunk cancel:$token")),
            ),
    )
}

private const val CHUNK_PREVIEW_LIMIT = 200

private fun blockChangeLine(change: BlockChangePlan): Component =
    Component
        .text("  block @ ${change.x},${change.y},${change.z} -> ", NamedTextColor.GRAY)
        .append(Component.text(change.targetState ?: change.targetMaterialKey, NamedTextColor.GREEN))
