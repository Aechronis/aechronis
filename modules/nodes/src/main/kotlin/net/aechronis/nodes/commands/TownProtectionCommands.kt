package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.commands.arguments.ArgumentResident
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType

class TownPermissionsCommand : NodesCommand("permissions", "perms") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/town permissions")
            Message.print(player, "/town permissions <type|all> <group> <flag>")
        }

        val typeArg = ArgumentType.Word("type").from("all", "build", "destroy", "interact", "chests", "items", "income")
        val groupArg = ArgumentType.Word("group").from("town", "nation", "ally", "outsider", "trusted")
        val flagArg = ArgumentType.Word("flag").from("allow", "deny")

        addSyntax({ player, resident, town, context ->
            // print current town permissions
            Message.print(player, "Town Permissions:")
            for (perm in enumValues<TownPermissions>()) {
                val groups = town.permissions[perm]
                Message.print(player, "- ${perm}${ChatColor.WHITE}: $groups")
            }

            // print usage for leader, officers
            if (isTownStaff(resident, town)) {
                Message.print(player, "Usage:")
                Message.print(player, "/town permissions")
                Message.print(player, "/town permissions <type|all> <group> <flag>")
            }
        })

        addSyntax({ player, resident, town, context ->
            if (!requireTownStaff(player, resident, town, "Only the town leader or officers can do this")) return@addSyntax

            // match permissions and group
            val permissions: List<TownPermissions> = when (context[typeArg].lowercase()) {
                "all" -> enumValues<TownPermissions>().toList()

                "build" -> listOf(TownPermissions.BUILD)

                "destroy" -> listOf(TownPermissions.DESTROY)

                "interact" -> listOf(TownPermissions.INTERACT)

                "chests" -> listOf(TownPermissions.CHESTS)

                "items" -> listOf(TownPermissions.USE_ITEMS)

                "income" -> listOf(TownPermissions.INCOME)

                else -> {
                    Message.error(player, "Invalid permissions type ${context[typeArg]}. Valid options: all, build, destroy, interact, chests, items, income")
                    return@addSyntax
                }
            }

            val group: PermissionsGroup = when (context[groupArg].lowercase()) {
                "town" -> PermissionsGroup.TOWN

                "nation" -> PermissionsGroup.NATION

                "ally" -> PermissionsGroup.ALLY

                "outsider" -> PermissionsGroup.OUTSIDER

                "trusted" -> PermissionsGroup.TRUSTED

                else -> {
                    Message.error(player, "Invalid permissions group ${context[groupArg]}. Valid options: town, nation, ally, outsider, trusted")
                    return@addSyntax
                }
            }

            // get flag state (allow/deny)
            val flag = when (context[flagArg].lowercase()) {
                "allow",
                "true",
                -> {
                    true
                }

                "deny",
                "false",
                -> {
                    false
                }

                else -> {
                    Message.error(player, "Invalid permissions flag ${context[flagArg]}. Valid options: allow, deny")
                    return@addSyntax
                }
            }

            Town.setPermissions(town, permissions, group, flag)

            Message.print(player, "Set permissions for ${town.name}: $permissions $group $flag")
        }, typeArg, groupArg, flagArg)
    }
}

class TownProtectCommand : NodesCommand("protect") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "[Nodes] Town protect commands:")
            Message.print(player, "/town protect${ChatColor.WHITE}: Toggle protecting chests")
            Message.print(player, "/town protect show${ChatColor.WHITE}: Show protected chests")
        }

        addSyntax({ player, resident, town, context ->
            if (!requireTownStaff(player, resident, town, "Only leaders and officers can protect chests")) return@addSyntax

            if (resident.isProtectingChests) {
                Resident.stopProtectingChests(resident)
                Message.print(player, "${ChatColor.DARK_AQUA}Stopped protecting chests.")
            } else {
                Resident.startProtectingChests(resident)
                Message.print(player, "Click on a chest to protect or unprotect it. Use \"/t protect\" again to stop protecting, or click a non-chest block to stop.")
            }
        })

        addSubcommand(TownProtectShowCommand())
    }
}

class TownProtectShowCommand : NodesCommand("show") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town protect show")
        }

        addSyntax({ player, resident, town, context ->
            Message.print(player, "Protected chests:")
            // print protected chests
            for (block in town.protectedBlocks) {
                Message.print(player, "${ChatColor.WHITE}${MinecraftServer.getInstanceManager().instances.first().getBlock(block).name()}: x: ${block.blockX}, y: ${block.blockY}, z: ${block.blockZ}")
            }

            Town.showProtectedChests(town, resident)
        })
    }
}

class TownTrustCommand : NodesCommand("trust") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town trust <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            if (!requireTownStaff(player, resident, town, "Only leaders and officers can trust/untrust players")) return@addSyntax

            if (!requireTownMember(player, context[playerArg], town)) return@addSyntax

            // set player trust
            Resident.setTrust(context[playerArg], true)
            Message.print(player, "${context[playerArg].name} is now marked as trusted")
        }, playerArg)
    }
}

class TownUntrustCommand : NodesCommand("untrust") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town untrust <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            if (!requireTownStaff(player, resident, town, "Only leaders and officers can trust/untrust players")) return@addSyntax

            // get other resident
            if (context[playerArg] == null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }

            if (!requireTownMember(player, context[playerArg], town)) return@addSyntax

            // set player trust
            Resident.setTrust(context[playerArg], false)
            Message.print(player, "${ChatColor.DARK_AQUA}${context[playerArg].name} is marked as untrusted")
        }, playerArg)
    }
}
