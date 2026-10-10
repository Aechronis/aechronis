package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class TownMinimapCommand : NodesCommand("minimap") {
    init {
        setDefaultExecutor { player, resident, _ ->
            Message.print(player, "[Nodes] Minimap settings:")
            Message.print(player, "/town minimap toggle${ChatColor.WHITE}: Toggle the resource map")
            Message.print(player, "/town minimap position <top|bottom> <left|right>${ChatColor.WHITE}: Move the map")
            Message.print(player, "/town minimap shift${ChatColor.WHITE}: Toggle enlarging the map while sneaking")
            Message.print(player, "/town minimap northlock${ChatColor.WHITE}: Toggle locking the map to north instead of spinning with your view")
            Message.print(
                player,
                "Map: ${if (resident.minimapEnabled) "enabled" else "disabled"}, position: ${resident.minimapPosition.id}, " +
                    "shifting: ${if (resident.minimapShiftEnabled) "enabled" else "disabled"}, " +
                    "north lock: ${if (resident.minimapNorthLocked) "enabled" else "disabled"}",
            )
        }

        addSubcommand(TownMinimapToggleCommand())
        addSubcommand(TownMinimapPositionCommand())
        addSubcommand(TownMinimapShiftCommand())
        addSubcommand(TownMinimapNorthLockCommand())
    }
}

class TownMinimapToggleCommand : NodesCommand("toggle") {
    init {
        setDefaultExecutor { player, resident, _ ->
            val enabled = !resident.minimapEnabled
            resident.setMinimapEnabled(enabled)
            Message.print(player, "Resource map ${if (enabled) "enabled" else "disabled"}")
        }
    }
}

class TownMinimapPositionCommand : NodesCommand("position", null, "location") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /town minimap position <top|bottom> <left|right>")
        }

        val verticalArg = ArgumentType.Word("vertical").from("top", "bottom")
        val horizontalArg = ArgumentType.Word("horizontal").from("left", "right")
        addSyntax({ player, resident, context ->
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

class TownMinimapShiftCommand : NodesCommand("shift", null, "shifting") {
    init {
        setDefaultExecutor { player, resident, _ ->
            val enabled = resident.toggleMinimapShift()
            Message.print(player, "Resource map shifting while sneaking ${if (enabled) "enabled" else "disabled"}")
        }
    }
}

class TownMinimapNorthLockCommand : NodesCommand("northlock", null, "rotation") {
    init {
        setDefaultExecutor { player, resident, _ ->
            val locked = resident.toggleMinimapNorthLock()
            Message.print(
                player,
                if (locked) "Resource map locked to north" else "Resource map now spins with your view",
            )
        }
    }
}
