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
    token: String,
) {
    val lines = mutableListOf<Component>()
    plan.blockChanges.forEach { lines += blockChangeLine(it) }
    if (plan.skippedBlockCount > 0) {
        lines +=
            Component.text(
                "  * ${plan.skippedBlockCount} historical entries skipped: no recorded instance/state",
                NamedTextColor.DARK_GRAY,
            )
    }

    val targetTime = timeFormat.format(Instant.ofEpochMilli(plan.targetTs))
    val title = "Rollback preview: ${plan.totalChangeCount} changes -> state as of $targetTime"

    Pages.send(player, title, lines)

    player.sendMessage(
        Component
            .text("[Confirm Rollback]", NamedTextColor.RED)
            .hoverEvent(HoverEvent.showText(Component.text("This will mutate the world. Click to apply.")))
            .clickEvent(ClickEvent.runCommand("/logger rollback confirm:$token"))
            .append(Component.text("  "))
            .append(
                Component
                    .text("[Cancel]", NamedTextColor.GRAY)
                    .hoverEvent(HoverEvent.showText(Component.text("Discard this preview without applying it.")))
                    .clickEvent(ClickEvent.runCommand("/logger rollback cancel:$token")),
            ),
    )
}

fun showRollbackResult(
    player: Player,
    plan: RollbackPlan,
) {
    player.sendMessage(
        Component.text(
            "[Logger] Rollback applied: ${plan.totalChangeCount} changes reverted. Use /logger undo to revert this.",
            NamedTextColor.GOLD,
        ),
    )
}

private fun blockChangeLine(change: BlockChangePlan): Component =
    Component
        .text("  block @ ${change.x},${change.y},${change.z} -> ", NamedTextColor.GRAY)
        .append(Component.text(change.restoreState ?: change.restoreMaterialKey, NamedTextColor.GREEN))
