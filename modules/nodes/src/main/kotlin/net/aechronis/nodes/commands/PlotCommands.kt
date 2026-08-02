package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.commands.arguments.ArgumentResident
import net.aechronis.nodes.commands.arguments.ArgumentSanitizedString
import net.aechronis.nodes.commands.arguments.ArgumentTown
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class TownPlotCommand : NodesCommand("plot") {
    init {
        setDefaultExecutor { player, _, _ -> printPlotHelp(player) }
        addSubcommand(TownPlotToggleCommand())
        addSubcommand(TownPlotCreateCommand())
        addSubcommand(TownPlotRedefineCommand())
        addSubcommand(TownPlotPermissionsCommand())
        addSubcommand(TownPlotListCommand())
        addSubcommand(TownPlotDeleteCommand())
    }
}

class TownPlotToggleCommand : NodesCommand("toggle") {
    init {
        setDefaultExecutor { player, _, _ -> Message.print(player, "Usage: /town plot toggle") }
        addSyntax({ player, resident, town, _ ->
            if (!isTownStaff(resident, town)) {
                Message.error(player, "Only the town leader or officers can select plots")
                return@addSyntax
            }
            if (resident.plotSelectionEnabled) {
                Resident.stopPlotSelection(resident)
                Message.print(player, "Plot selection disabled")
            } else {
                Resident.startPlotSelection(resident)
                Message.print(player, "Plot selection enabled. Left-click corner 1 and right-click corner 2")
            }
        })
    }
}

class TownPlotCreateCommand : NodesCommand("create") {
    init {
        val nameArg = ArgumentSanitizedString.create("plot-name")
        setDefaultExecutor { player, _, _ -> Message.print(player, "Usage: /town plot create <plot-name>") }
        addSyntax({ player, resident, town, context ->
            if (!isTownStaff(resident, town)) {
                Message.error(player, "Only the town leader or officers can create plots")
                return@addSyntax
            }
            val first = resident.plotCornerOne
            val second = resident.plotCornerTwo
            if (first == null || second == null) {
                Message.error(player, "Select two plot corners first with /town plot toggle")
                return@addSyntax
            }

            Plot.create(town, context[nameArg], first, second).getOrElse { error ->
                Message.error(player, "Failed to create plot: ${error.message}")
                return@addSyntax
            }
            resident.clearPlotSelection()
            Message.print(player, "Created plot \"${context[nameArg]}\"")
        }, nameArg)
    }
}

class TownPlotRedefineCommand : NodesCommand("redefine") {
    init {
        val nameArg = ArgumentSanitizedString.create("plot-name")
        setDefaultExecutor { player, _, _ -> Message.print(player, "Usage: /town plot redefine <plot-name>") }
        addSyntax({ player, resident, town, context ->
            if (!isTownStaff(resident, town)) {
                Message.error(player, "Only the town leader or officers can redefine plots")
                return@addSyntax
            }
            val plot = town.plots[context[nameArg]]
            if (plot == null) {
                Message.error(player, "Plot not found")
                return@addSyntax
            }
            val first = resident.plotCornerOne
            val second = resident.plotCornerTwo
            if (first == null || second == null) {
                Message.error(player, "Select two plot corners first with /town plot toggle")
                return@addSyntax
            }

            Plot.redefine(town, plot, first, second).getOrElse { error ->
                Message.error(player, "Failed to redefine plot: ${error.message}")
                return@addSyntax
            }
            resident.clearPlotSelection()
            Message.print(player, "Redefined plot \"${plot.name}\"")
        }, nameArg)
    }
}

class TownPlotPermissionsCommand : NodesCommand("permissions", "perms") {
    init {
        val plotArg = ArgumentSanitizedString.create("plot-name")
        val groupArg = ArgumentType.Word("group").from("town", "nation", "ally", "outsider", "trusted")
        val playerLiteral = ArgumentType.Literal("player")
        val playerArg = ArgumentResident.create("player-name")
        val permissionArg = plotPermissionArgument()
        val flagArg = plotFlagArgument()

        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /town plot permissions <plot> <group> <action|all> <allow|deny|inherit>")
            Message.print(player, "Usage: /town plot permissions <plot> player <player> <action|all> <allow|deny|inherit>")
        }

        addSyntax({ player, resident, town, context ->
            setGroupPlotPermission(player, resident, town, context[plotArg], context[groupArg], context[permissionArg], context[flagArg])
        }, plotArg, groupArg, permissionArg, flagArg)

        addSyntax({ player, resident, town, context ->
            setPlayerPlotPermission(player, resident, town, context[plotArg], context[playerArg], context[permissionArg], context[flagArg])
        }, plotArg, playerLiteral, playerArg, permissionArg, flagArg)
    }
}

class TownPlotListCommand : NodesCommand("list") {
    init {
        setDefaultExecutor { player, _, _ -> Message.print(player, "Usage: /town plot list") }
        addSyntax({ player, _, town, _ -> printPlots(player, town) })
    }
}

class TownPlotDeleteCommand : NodesCommand("delete") {
    init {
        val nameArg = ArgumentSanitizedString.create("plot-name")
        setDefaultExecutor { player, _, _ -> Message.print(player, "Usage: /town plot delete <plot-name>") }
        addSyntax({ player, resident, town, context ->
            if (!isTownStaff(resident, town)) {
                Message.error(player, "Only the town leader or officers can delete plots")
                return@addSyntax
            }
            val plot = town.plots[context[nameArg]]
            if (plot == null || !Plot.delete(town, plot)) {
                Message.error(player, "Plot not found")
                return@addSyntax
            }
            Message.print(player, "Deleted plot \"${plot.name}\"")
        }, nameArg)
    }
}

class NodesAdminTownPlotCommand : NodesCommand("plot", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ -> printAdminPlotHelp(player) }

        val toggleLiteral = ArgumentType.Literal("toggle")
        val createLiteral = ArgumentType.Literal("create")
        val redefineLiteral = ArgumentType.Literal("redefine")
        val permissionsLiteral = ArgumentType.Literal("permissions")
        val listLiteral = ArgumentType.Literal("list")
        val deleteLiteral = ArgumentType.Literal("delete")
        val townArg = ArgumentTown.create("town-name")
        val plotArg = ArgumentSanitizedString.create("plot-name")
        val createNameArg = ArgumentSanitizedString.create("create-plot-name")
        val redefineNameArg = ArgumentSanitizedString.create("redefine-plot-name")
        val deleteNameArg = ArgumentSanitizedString.create("delete-plot-name")
        val groupArg = ArgumentType.Word("group").from("town", "nation", "ally", "outsider", "trusted")
        val playerLiteral = ArgumentType.Literal("player")
        val playerArg = ArgumentResident.create("player-name")
        val permissionArg = plotPermissionArgument()
        val flagArg = plotFlagArgument()

        addSyntax({ player, resident, context ->
            if (resident.plotSelectionEnabled) {
                Resident.stopPlotSelection(resident)
                Message.print(player, "Plot selection disabled for ${context[townArg].name}")
            } else {
                Resident.startPlotSelection(resident)
                Message.print(player, "Plot selection enabled. Left-click corner 1 and right-click corner 2")
            }
        }, townArg, toggleLiteral)

        addSyntax({ player, resident, context ->
            createAdminPlot(player, resident, context[townArg], context[createNameArg])
        }, townArg, createLiteral, createNameArg)

        addSyntax({ player, resident, context ->
            val town = context[townArg]
            val plot = town.plots[context[redefineNameArg]]
            if (plot == null) {
                Message.error(player, "Plot not found")
                return@addSyntax
            }
            val first = resident.plotCornerOne
            val second = resident.plotCornerTwo
            if (first == null || second == null) {
                Message.error(player, "Select two plot corners first with the admin toggle command")
                return@addSyntax
            }
            Plot.redefine(town, plot, first, second).getOrElse { error ->
                Message.error(player, "Failed to redefine plot: ${error.message}")
                return@addSyntax
            }
            resident.clearPlotSelection()
            Message.print(player, "Redefined plot \"${plot.name}\" in ${town.name}")
        }, townArg, redefineLiteral, redefineNameArg)

        addSyntax({ player, _, context ->
            setGroupPlotPermission(player, null, context[townArg], context[plotArg], context[groupArg], context[permissionArg], context[flagArg])
        }, townArg, permissionsLiteral, plotArg, groupArg, permissionArg, flagArg)

        addSyntax({ player, _, context ->
            setPlayerPlotPermission(player, null, context[townArg], context[plotArg], context[playerArg], context[permissionArg], context[flagArg])
        }, townArg, permissionsLiteral, plotArg, playerLiteral, playerArg, permissionArg, flagArg)

        addSyntax({ player, _, context -> printPlots(player, context[townArg]) }, townArg, listLiteral)

        addSyntax({ player, _, context ->
            val town = context[townArg]
            val plot = town.plots[context[deleteNameArg]]
            if (plot == null || !Plot.delete(town, plot)) {
                Message.error(player, "Plot not found")
                return@addSyntax
            }
            Message.print(player, "Deleted plot \"${plot.name}\" from ${town.name}")
        }, townArg, deleteLiteral, deleteNameArg)
    }
}

private fun createAdminPlot(player: Player, resident: Resident, town: Town, name: String) {
    val first = resident.plotCornerOne
    val second = resident.plotCornerTwo
    if (first == null || second == null) {
        Message.error(player, "Select two plot corners first with the admin toggle command")
        return
    }
    Plot.create(town, name, first, second).getOrElse { error ->
        Message.error(player, "Failed to create plot: ${error.message}")
        return
    }
    resident.clearPlotSelection()
    Message.print(player, "Created plot \"$name\" in ${town.name}")
}

private fun setGroupPlotPermission(
    player: Player,
    resident: Resident?,
    town: Town,
    plotName: String,
    groupName: String,
    permissionName: String,
    flagName: String,
) {
    if (resident != null && !isTownStaff(resident, town)) {
        Message.error(player, "Only the town leader or officers can change plot permissions")
        return
    }
    val plot = town.plots[plotName]
    val group = parseGroup(groupName)
    val permissions = parsePlotPermissions(permissionName)
    val flag = parsePlotFlag(flagName)
    if (plot == null || group == null || permissions == null) {
        Message.error(player, "Invalid plot, group, action, or flag")
        return
    }
    Plot.setGroupPermissions(town, plot, group, permissions, flag)
    Message.print(player, "Updated ${town.name}/$plotName permission: $group $permissionName $flagName")
}

private fun setPlayerPlotPermission(
    player: Player,
    resident: Resident?,
    town: Town,
    plotName: String,
    target: Resident,
    permissionName: String,
    flagName: String,
) {
    if (resident != null && !isTownStaff(resident, town)) {
        Message.error(player, "Only the town leader or officers can change plot permissions")
        return
    }
    val plot = town.plots[plotName]
    val permissions = parsePlotPermissions(permissionName)
    val flag = parsePlotFlag(flagName)
    if (plot == null || permissions == null) {
        Message.error(player, "Invalid plot, action, or flag")
        return
    }
    Plot.setPlayerPermissions(town, plot, target, permissions, flag)
    Message.print(player, "Updated ${town.name}/$plotName permission for ${target.name}: $permissionName $flagName")
}

private fun parseGroup(value: String): PermissionsGroup? = when (value.lowercase()) {
    "town" -> PermissionsGroup.TOWN
    "nation" -> PermissionsGroup.NATION
    "ally" -> PermissionsGroup.ALLY
    "outsider" -> PermissionsGroup.OUTSIDER
    "trusted" -> PermissionsGroup.TRUSTED
    else -> null
}

private fun parsePlotPermissions(value: String): List<TownPermissions>? = when (value.lowercase()) {
    "all" -> enumValues<TownPermissions>().toList()
    "build" -> listOf(TownPermissions.BUILD)
    "break", "destroy" -> listOf(TownPermissions.DESTROY)
    "interact" -> listOf(TownPermissions.INTERACT)
    "chests" -> listOf(TownPermissions.CHESTS)
    "items" -> listOf(TownPermissions.USE_ITEMS)
    "income" -> listOf(TownPermissions.INCOME)
    else -> null
}

private fun parsePlotFlag(value: String): Boolean? = when (value.lowercase()) {
    "allow", "true" -> true
    "deny", "false" -> false
    "inherit" -> null
    else -> null
}

private fun plotPermissionArgument() = ArgumentType.Word("action").from("all", "build", "break", "destroy", "interact", "chests", "items", "income")

private fun plotFlagArgument() = ArgumentType.Word("flag").from("allow", "deny", "inherit")

private fun isTownStaff(resident: Resident, town: Town): Boolean = resident === town.leader || town.officers.contains(resident)

private fun printPlots(sender: Player, town: Town) {
    Message.print(sender, "${ChatColor.BOLD}Plots in ${town.name}:")
    if (town.plots.isEmpty()) {
        Message.print(sender, "${ChatColor.GRAY}None")
        return
    }
    town.plots.values.forEach { plot ->
        Message.print(
            sender,
            "- ${plot.name}${ChatColor.WHITE}: (${plot.minX},${plot.minY},${plot.minZ}) to (${plot.maxX},${plot.maxY},${plot.maxZ})",
        )
    }
}

private fun printPlotHelp(sender: Player) {
    Message.print(sender, "${ChatColor.BOLD}[Nodes] Town plot commands:")
    Message.print(sender, "/town plot toggle${ChatColor.WHITE}: Toggle 3D corner selection")
    Message.print(sender, "/town plot create <name>${ChatColor.WHITE}: Create a plot from two selected corners")
    Message.print(sender, "/town plot redefine <name>${ChatColor.WHITE}: Redefine an existing plot")
    Message.print(sender, "/town plot permissions ...${ChatColor.WHITE}: Set plot permissions")
    Message.print(sender, "/town plot list${ChatColor.WHITE}: List plots")
    Message.print(sender, "/town plot delete <name>${ChatColor.WHITE}: Delete a plot")
}

private fun printAdminPlotHelp(sender: Player) {
    Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin town plot commands:")
    Message.print(sender, "/nda town plot <town> toggle")
    Message.print(sender, "/nda town plot <town> create <name>")
    Message.print(sender, "/nda town plot <town> redefine <name>")
    Message.print(sender, "/nda town plot <town> permissions ...")
    Message.print(sender, "/nda town plot <town> list")
    Message.print(sender, "/nda town plot <town> delete <name>")
}
