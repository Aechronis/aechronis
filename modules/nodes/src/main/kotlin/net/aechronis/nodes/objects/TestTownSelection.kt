package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.utils.hasPermission
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.dialog.Dialog
import net.minestom.server.dialog.DialogAction
import net.minestom.server.dialog.DialogActionButton
import net.minestom.server.dialog.DialogAfterAction
import net.minestom.server.dialog.DialogBody
import net.minestom.server.dialog.DialogMetadata
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerCustomClickEvent

internal enum class TestTownSide {
    RED,
    BLUE,
}

internal fun testTownLockedSide(redPopulation: Int, bluePopulation: Int, difference: Int): TestTownSide? = when {
    redPopulation - bluePopulation >= difference.coerceAtLeast(1) -> TestTownSide.RED
    bluePopulation - redPopulation >= difference.coerceAtLeast(1) -> TestTownSide.BLUE
    else -> null
}

/** Handles the optional two-town team selection mode. */
object TestTownSelection {
    private val lockedTownAction = Key.key("nodes", "test_town_locked")
    private val cancelAction = Key.key("nodes", "test_town_cancel")

    private data class Targets(
        val red: Town,
        val blue: Town,
    ) {
        fun contains(town: Town): Boolean = town === red || town === blue
    }

    fun isEnabled(): Boolean = Nodes.config.testTownSelectionEnabled

    fun join(player: Player, resident: Resident, requestedTown: Town): Result<Town> = runCatching {
        check(isEnabled()) { "Test town selection is disabled" }
        val targets = targets() ?: error(configurationError())
        check(targets.contains(requestedTown)) { "You may only join ${targets.red.name} or ${targets.blue.name}" }

        val currentTown = resident.town
        check(currentTown == null || targets.contains(currentTown)) {
            "You may only switch between ${targets.red.name} and ${targets.blue.name}"
        }
        check(currentTown !== requestedTown) { "You are already a member of ${requestedTown.name}" }
        check(lockedTown(targets) !== requestedTown) {
            "${requestedTown.name} is currently overpowered; join ${other(targets, requestedTown).name} instead"
        }
        check(currentTown?.leader !== resident) { "Transfer town leadership before switching towns" }

        if (currentTown != null) Town.removeResident(currentTown, resident)
        check(Town.addResident(requestedTown, resident, bypassTestTownSelection = true)) { "Could not join ${requestedTown.name}" }
        player.teleport(requestedTown.spawnpoint)
        requestedTown
    }

    /** Show the picker to unassigned, non-staff players when both configured towns exist. */
    fun showJoinDialog(player: Player, resident: Resident) {
        if (!isEnabled() || player.hasPermission("*") || resident.town != null) return
        val targets = targets()
        if (targets == null) {
            Message.error(player, configurationError())
            return
        }
        player.showDialog(joinDialog(targets))
    }

    fun init() {
        Nodes.eventNode.addListener(PlayerCustomClickEvent::class.java, this::onCustomClick)
    }

    private fun onCustomClick(event: PlayerCustomClickEvent) {
        if (event.key != lockedTownAction) return
        if (targets() == null) return
        Message.error(event.player, "That town is currently overpowered; join the other town instead")
    }

    private fun joinDialog(targets: Targets): Dialog.MultiAction {
        val locked = lockedTown(targets)
        val body = buildList<DialogBody> {
            add(DialogBody.PlainMessage(Component.text("Choose a town to join.", NamedTextColor.GRAY), 300))
            locked?.let { town ->
                add(
                    DialogBody.PlainMessage(
                        Component.text("${town.name} is overpowered and cannot be selected.", NamedTextColor.RED),
                        300,
                    ),
                )
            }
        }
        val metadata = DialogMetadata(
            Component.text("Choose your town", NamedTextColor.GOLD),
            null,
            true,
            false,
            DialogAfterAction.CLOSE,
            body,
            emptyList(),
        )
        return Dialog.MultiAction(
            metadata,
            listOf(townButton(targets.red, locked === targets.red), townButton(targets.blue, locked === targets.blue)),
            DialogActionButton(
                Component.text("Cancel", NamedTextColor.GRAY),
                null,
                120,
                DialogAction.Custom(cancelAction, CompoundBinaryTag.empty()),
            ),
            2,
        )
    }

    private fun townButton(town: Town, locked: Boolean): DialogActionButton = if (locked) {
        // Minecraft's dialog protocol has no disabled button state. A custom action
        // keeps the option visibly locked and prevents it from running the command.
        DialogActionButton(
            Component.text("${town.name} (Locked)", NamedTextColor.RED),
            Component.text("This town has too many players", NamedTextColor.GRAY),
            150,
            DialogAction.Custom(lockedTownAction, CompoundBinaryTag.empty()),
        )
    } else {
        DialogActionButton(
            Component.text("Join ${town.name}", NamedTextColor.GREEN),
            Component.text("Join and teleport to this town's spawn", NamedTextColor.GRAY),
            150,
            DialogAction.RunCommand("/town join ${town.name}"),
        )
    }

    private fun targets(): Targets? {
        if (!isEnabled()) return null
        val redName = Nodes.config.testTownRedName
        val blueName = Nodes.config.testTownBlueName
        if (redName.equals(blueName, ignoreCase = true)) return null
        val red = Town.fromName(redName) ?: return null
        val blue = Town.fromName(blueName) ?: return null
        return Targets(red, blue)
    }

    private fun lockedTown(targets: Targets): Town? = when (
        testTownLockedSide(
            population(targets.red),
            population(targets.blue),
            Nodes.config.testTownPopulationDifference,
        )
    ) {
        TestTownSide.RED -> targets.red
        TestTownSide.BLUE -> targets.blue
        null -> null
    }

    private fun population(town: Town): Int = town.residents.size + if (town.leader != null && !town.residents.contains(town.leader)) 1 else 0

    private fun other(targets: Targets, town: Town): Town = if (town === targets.red) targets.blue else targets.red

    private fun configurationError(): String = "Test town selection requires configured towns '${Nodes.config.testTownRedName}' and '${Nodes.config.testTownBlueName}'"
}
