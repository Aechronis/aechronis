package net.aechronis.nodes.commands

import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.WaypointMenu

/** Per-player permanent and death waypoint menus. */
class WaypointCommand : NodesCommand("waypoint", null, "wp") {
    init {
        setDefaultExecutor { player, resident, _ ->
            WaypointMenu.openBrowse(player, resident)
        }

        addSubcommand(WaypointCreateCommand())
    }
}

private class WaypointCreateCommand : NodesCommand("create") {
    init {
        setDefaultExecutor { player, resident, _ ->
            WaypointMenu.openCreate(player, resident)
        }
    }
}
