package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.MinimapMode
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

/** Position, shift and north lock only affect the modern HUD map; say so instead of silently doing nothing. */
private fun rejectLegacy(player: Player, resident: Resident): Boolean {
    if (resident.minimapMode != MinimapMode.LEGACY) return false
    Message.error(player, "That only applies to the modern map; use /town minimap mode modern")
    return true
}

class TownMinimapCommand : NodesCommand("minimap") {
    init {
        setDefaultExecutor { player, resident, _ ->
            Message.print(player, "[Nodes] Minimap settings:")
            Message.print(player, "/town minimap mode <off|modern|legacy>${ChatColor.WHITE}: Turn the map off, or pick the HUD map or the simple sidebar map")
            Message.print(player, "/town minimap position <top|bottom> <left|right>${ChatColor.WHITE}: Move the map")
            Message.print(player, "/town minimap shift${ChatColor.WHITE}: Toggle enlarging the map while sneaking")
            Message.print(player, "/town minimap northlock${ChatColor.WHITE}: Toggle locking the map to north instead of spinning with your view")
            Message.print(
                player,
                "Mode: ${resident.minimapMode.id}, position: ${resident.minimapPosition.id}, " +
                    "shifting: ${if (resident.minimapShiftEnabled) "enabled" else "disabled"}, " +
                    "north lock: ${if (resident.minimapNorthLocked) "enabled" else "disabled"}",
            )
        }

        addSubcommand(TownMinimapModeCommand())
        addSubcommand(TownMinimapPositionCommand())
        addSubcommand(TownMinimapShiftCommand())
        addSubcommand(TownMinimapNorthLockCommand())
    }
}

class TownMinimapModeCommand : NodesCommand("mode") {
    init {
        setDefaultExecutor { player, resident, _ ->
            Message.print(player, "Usage: /town minimap mode <off|modern|legacy> (currently ${resident.minimapMode.id})")
        }

        val modeArg = ArgumentType.Word("style").from(*MinimapMode.entries.map { it.id }.toTypedArray())
        addSyntax({ player, resident, context ->
            val mode = MinimapMode.entries.first { it.id == context[modeArg] }
            resident.setMinimapMode(mode)
            Message.print(player, "Map mode: ${mode.id}")
        }, modeArg)
    }
}

class TownMinimapPositionCommand : NodesCommand("position") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /town minimap position <top|bottom> <left|right>")
        }

        val verticalArg = ArgumentType.Word("vertical").from("top", "bottom")
        val horizontalArg = ArgumentType.Word("horizontal").from("left", "right")
        addSyntax({ player, resident, context ->
            if (rejectLegacy(player, resident)) return@addSyntax
            setMinimapPosition(player, resident, "${context[verticalArg]}-${context[horizontalArg]}")
        }, verticalArg, horizontalArg)
    }

    private fun setMinimapPosition(player: Player, resident: Resident, id: String) {
        val position = MinimapPosition.fromId(id)
        if (position == null) {
            Message.error(player, "Invalid minimap position")
            return
        }
        resident.setMinimapPosition(position)
        Message.print(player, "Resource map moved to ${position.id}")
    }
}

class TownMinimapShiftCommand : NodesCommand("shift") {
    init {
        setDefaultExecutor { player, resident, _ ->
            if (rejectLegacy(player, resident)) return@setDefaultExecutor
            val enabled = resident.toggleMinimapShift()
            Message.print(player, "Resource map shifting while sneaking ${if (enabled) "enabled" else "disabled"}")
        }
    }
}

class TownMinimapNorthLockCommand : NodesCommand("northlock") {
    init {
        setDefaultExecutor { player, resident, _ ->
            if (rejectLegacy(player, resident)) return@setDefaultExecutor
            val locked = resident.toggleMinimapNorthLock()
            Message.print(
                player,
                if (locked) "Resource map locked to north" else "Resource map now spins with your view",
            )
        }
    }
}
