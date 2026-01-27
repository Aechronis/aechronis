/**
 * /town (/s) command
 */

package luna.nodes.nodes.commands

import luna.nodes.nodes.Message
import luna.nodes.nodes.Nodes
import luna.nodes.nodes.WorldMap
import luna.nodes.nodes.commands.arguments.ArgumentResident
import luna.nodes.nodes.commands.arguments.ArgumentTown
import luna.nodes.nodes.constants.PermissionsGroup
import luna.nodes.nodes.constants.TownPermissions
import luna.nodes.nodes.objects.Command
import luna.nodes.nodes.objects.Coord
import luna.nodes.nodes.objects.Resident
import luna.nodes.nodes.utils.ChatColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import net.minestom.server.timer.TaskSchedule

// ==================================================
// Constants for /t map
//
// symbols
const val SHADE = "\u2592" // medium shade
const val HOME = "\u2588" // full solid block
const val CORE = "\u256B" // core chunk H
const val CONQUERED0 = "\u2561" // captured chunk
const val CONQUERED1 = "\u255F" // other chunk flag symbol

const val MAP_STR_BEGIN = "    "

val MAP_STR_END = arrayOf(
    "",
    "       ${ChatColor.GOLD}N",
    "     ${ChatColor.GOLD}W + E",
    "       ${ChatColor.GOLD}S",
    "",
    "  ${ChatColor.GRAY}${SHADE}${ChatColor.DARK_GRAY}$SHADE ${ChatColor.GRAY}- Unclaimed",
    "  ${ChatColor.GREEN}${SHADE}${ChatColor.DARK_GREEN}$SHADE - Town",
    "  ${ChatColor.YELLOW}${SHADE}${ChatColor.GOLD}$SHADE - Neutral",
    "  ${ChatColor.AQUA}${SHADE}${ChatColor.DARK_AQUA}$SHADE - Ally",
    "  ${ChatColor.RED}${SHADE}${ChatColor.DARK_RED}$SHADE - Enemy",
    "",
    "  ${ChatColor.WHITE}$HOME - Home territory",
    "  ${ChatColor.WHITE}$CORE - Core chunk",
    "  ${ChatColor.WHITE}$CONQUERED0,$CONQUERED1 - Captured",
    "",
    "",
    "",
    "",
)
// ==================================================

class TownCommand : Command("t", "town") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Town commands:")
            Message.print(sender, "/town promote${ChatColor.WHITE}: Give officer rank to resident")
            Message.print(sender, "/town demote${ChatColor.WHITE}: Remove officer rank from resident")
            Message.print(sender, "/town apply${ChatColor.WHITE}: Apply to join a town")
            Message.print(sender, "/town invite${ChatColor.WHITE}: Invite a player to your town")
            Message.print(sender, "/town leave${ChatColor.WHITE}: Leave your town")
            Message.print(sender, "/town kick${ChatColor.WHITE}: Kick player from your town")
            Message.print(sender, "/town spawn${ChatColor.WHITE}: Teleport to your town spawnpoint")
            Message.print(sender, "/town setspawn${ChatColor.WHITE}: Set a new town spawnpoint")
            Message.print(sender, "/town list${ChatColor.WHITE}: List all towns")
            Message.print(sender, "/town info${ChatColor.WHITE}: View town details")
            Message.print(sender, "/town online${ChatColor.WHITE}: View town's online players")
            Message.print(sender, "/town map${ChatColor.WHITE}: View world map")
            Message.print(sender, "/town minimap${ChatColor.WHITE}: Toggle sidebar world minimap")
            Message.print(sender, "/town permissions${ChatColor.WHITE}: Set town protection permissions")
            Message.print(sender, "/town protect${ChatColor.WHITE}: Protect town chests")
            Message.print(sender, "/town trust${ChatColor.WHITE}: Mark player as trusted")
            Message.print(sender, "/town untrust${ChatColor.WHITE}: Remove player from trusted")
            Message.print(sender, "/town fly${ChatColor.WHITE}: Fly inside your town")
        }

        // no args, print current town info
        addSyntax({ player, resident, context ->
            if (resident.town != null) {
                resident.town!!.printInfo(player)
            } else {
                Message.print(player, "${ChatColor.BOLD}[Nodes] Town commands:")
                Message.print(player, "/town promote${ChatColor.WHITE}: Give officer rank to resident")
                Message.print(player, "/town demote${ChatColor.WHITE}: Remove officer rank from resident")
                Message.print(player, "/town apply${ChatColor.WHITE}: Apply to join a town")
                Message.print(player, "/town invite${ChatColor.WHITE}: Invite a player to your town")
                Message.print(player, "/town leave${ChatColor.WHITE}: Leave your town")
                Message.print(player, "/town kick${ChatColor.WHITE}: Kick player from your town")
                Message.print(player, "/town spawn${ChatColor.WHITE}: Teleport to your town spawnpoint")
                Message.print(player, "/town setspawn${ChatColor.WHITE}: Set a new town spawnpoint")
                Message.print(player, "/town list${ChatColor.WHITE}: List all towns")
                Message.print(player, "/town info${ChatColor.WHITE}: View town details")
                Message.print(player, "/town online${ChatColor.WHITE}: View town's online players")
                Message.print(player, "/town map${ChatColor.WHITE}: View world map")
                Message.print(player, "/town minimap${ChatColor.WHITE}: Toggle sidebar world minimap")
                Message.print(player, "/town permissions${ChatColor.WHITE}: Set town protection permissions")
                Message.print(player, "/town protect${ChatColor.WHITE}: Protect town chests")
                Message.print(player, "/town trust${ChatColor.WHITE}: Mark player as trusted")
                Message.print(player, "/town untrust${ChatColor.WHITE}: Remove player from trusted")
                Message.print(player, "/town fly${ChatColor.WHITE}: Fly inside your town")
            }
        })

        addSubcommand(TownHelpCommand())
        addSubcommand(TownPromoteCommand())
        addSubcommand(TownDemoteCommand())
        addSubcommand(TownApplyCommand())
        addSubcommand(TownInviteCommand())
        addSubcommand(TownAcceptCommand())
        addSubcommand(TownDenyCommand())
        addSubcommand(TownLeaveCommand())
        addSubcommand(TownKickCommand())
        addSubcommand(TownSpawn())
        addSubcommand(TownSetSpawn())
        addSubcommand(TownListCommand())
        addSubcommand(TownInfoCommand())
        addSubcommand(TownOnlineCommand())
        addSubcommand(TownIncomeCommand())
        addSubcommand(TownMapCommand())
        addSubcommand(TownMinimapCommand())
        addSubcommand(TownPermissionsCommand())
        addSubcommand(TownProtectCommand())
        addSubcommand(TownTrustCommand())
        addSubcommand(TownUntrustCommand())
        addSubcommand(TownFlyCommand())
    }
}

class TownHelpCommand : Command("help") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Town commands:")
            Message.print(sender, "/town promote${ChatColor.WHITE}: Give officer rank to resident")
            Message.print(sender, "/town demote${ChatColor.WHITE}: Remove officer rank from resident")
            Message.print(sender, "/town apply${ChatColor.WHITE}: Apply to join a town")
            Message.print(sender, "/town invite${ChatColor.WHITE}: Invite a player to your town")
            Message.print(sender, "/town leave${ChatColor.WHITE}: Leave your town")
            Message.print(sender, "/town kick${ChatColor.WHITE}: Kick player from your town")
            Message.print(sender, "/town spawn${ChatColor.WHITE}: Teleport to your town spawnpoint")
            Message.print(sender, "/town setspawn${ChatColor.WHITE}: Set a new town spawnpoint")
            Message.print(sender, "/town list${ChatColor.WHITE}: List all towns")
            Message.print(sender, "/town info${ChatColor.WHITE}: View town details")
            Message.print(sender, "/town online${ChatColor.WHITE}: View town's online players")
            Message.print(sender, "/town map${ChatColor.WHITE}: View world map")
            Message.print(sender, "/town minimap${ChatColor.WHITE}: Toggle sidebar world minimap")
            Message.print(sender, "/town permissions${ChatColor.WHITE}: Set town protection permissions")
            Message.print(sender, "/town protect${ChatColor.WHITE}: Protect town chests")
            Message.print(sender, "/town trust${ChatColor.WHITE}: Mark player as trusted")
            Message.print(sender, "/town untrust${ChatColor.WHITE}: Remove player from trusted")
            Message.print(sender, "/town fly${ChatColor.WHITE}: Fly inside your town")
        }
    }
}

class TownPromoteCommand : Command("promote", "officer") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town promote <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            // check if player is town leader
            val leader = town.leader
            if (resident !== leader) {
                Message.error(player, "You are not the town leader")
                return@addSyntax
            }

            if (context[playerArg] === resident) {
                Message.error(player, "You are already the town leader")
                return@addSyntax
            }

            if (context[playerArg].town !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            val targetPlayer = context[playerArg].player()

            // add officer
            if (!town.officers.contains(context[playerArg])) {
                Nodes.townAddOfficer(town, context[playerArg])
                Message.print(player, "Made ${context[playerArg].name} a town officer")

                if (targetPlayer !== null) {
                    Message.print(targetPlayer, "You are now a town officer")
                }
            }
        }, playerArg)
    }
}

class TownDemoteCommand : Command("demote") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town demote <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->

            // check if player is town leader
            val leader = town.leader
            if (resident !== leader) {
                Message.error(player, "You are not the town leader")
                return@addSyntax
            }

            if (context[playerArg] === resident) {
                Message.error(player, "You are already the town leader")
                return@addSyntax
            }

            val targetTown = context[playerArg].town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            val targetPlayer = context[playerArg].player()

            // remove officer
            if (town.officers.contains(context[playerArg])) {
                Nodes.townRemoveOfficer(town, context[playerArg])
                Message.print(player, "Removed ${context[playerArg].name} from town officers")

                if (targetPlayer !== null) {
                    Message.error(targetPlayer, "You are no longer a town officer")
                }
            }
        }, playerArg)
    }
}

class TownApplyCommand : Command("apply", "join") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town apply <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            if (resident.town != null) {
                Message.error(player, "You are already a member of a town")
                return@addSyntax
            }

            if (context[townArg].applications.containsKey(resident)) {
                Message.error(player, "You have already applied to ${context[townArg].name}")
                return@addSyntax
            }

            val approvers: ArrayList<Player> = ArrayList()
            MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context[townArg].leader!!.name)?.let { player ->
                approvers.add(player)
            }
            context[townArg].officers.forEach { officer ->
                MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(officer.name)?.let { player ->
                    approvers.add(player)
                }
            }

            if (approvers.isEmpty()) {
                Message.error(player, "There are no officers online from ${context[townArg].name} to receive your application")
                return@addSyntax
            }

            approvers.forEach { approver ->
                Message.print(approver, "${resident.name} has applied to join to your town. \nType \"/t accept\" to let them in or \"/t reject\" to refuse the offer.")
            }
            Message.print(player, "Your application has been sent")

            context[townArg].applications.put(
                resident,
                MinecraftServer.getSchedulerManager()
                    .buildTask {
                        if (resident.town == null) {
                            player.sendMessage("No one in ${context[townArg].name} responded to your application!")
                            context[townArg].applications.remove(resident)
                        }
                    }
                    .delay(TaskSchedule.tick(1200))
                    .schedule(),
            )
        }, townArg)
    }
}

class TownInviteCommand : Command("invite") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town invite <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            val invitee: Player? = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context[playerArg].name)
            if (invitee == null) {
                Message.error(player, "That player is not online")
                return@addSyntax
            } else if (invitee == player) {
                Message.error(player, "You're already in your town")
                return@addSyntax
            }

            if (context[playerArg].invitingTown == town) {
                Message.error(player, "This player has already been invited to the town")
                return@addSyntax
            } else if (context[playerArg].invitingTown != null) {
                Message.error(player, "This player is considering another town invitation")
                return@addSyntax
            }
            val inviteeTown = context[playerArg].town
            if (inviteeTown != null) {
                Message.error(player, "This player is already a member of a town")
                return@addSyntax
            }

            if (town.leader === resident || town.officers.contains(resident)) {
                Message.print(player, "${invitee.username} has been invited to your town.")
                Message.print(invitee, "You have been invited to become a member of ${town.name}.\nType \"/t accept\" to join the town or \"/t reject\" to refuse the offer.")
                context[playerArg].invitingTown = town
                context[playerArg].invitingPlayer = player
                context[playerArg].inviteThread = MinecraftServer.getSchedulerManager()
                    .buildTask {
                        if (context[playerArg].invitingPlayer == player) {
                            Message.print(player, "${invitee.username} didn't respond to your town invitation!")
                            context[playerArg].invitingTown = null
                            context[playerArg].invitingPlayer = null
                            context[playerArg].inviteThread = null
                        }
                    }
                    .delay(TaskSchedule.tick(1200))
                    .schedule()
            } else {
                Message.error(player, "You are not allowed to invite new members")
            }
        }, playerArg)
    }
}

class TownAcceptCommand : Command("accept") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/town accept")
            Message.print(sender, "/town accept <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, context ->
            val town = resident.town
            if (town == null) {
                if (resident.invitingTown == null) {
                    Message.error(player, "You have not been invited to any town or your invitation expired")
                    return@addSyntax
                }

                Message.print(player, "You are now a member of ${resident.invitingTown?.name}! Type \"/t spawn\" to teleport to your new town.")
                Message.print(resident.invitingPlayer, "${resident.name} has accepted your invitation!")

                Nodes.addResidentToTown(resident.invitingTown!!, resident)
                resident.invitingTown = null
                resident.invitingPlayer = null
                resident.inviteThread = null
            } else {
                if (town.leader != resident && !town.officers.contains(resident)) {
                    Message.error(player, "You aren't allowed to consider town applications")
                    return@addSyntax
                }

                if (town.applications.isEmpty()) {
                    Message.error(player, "There are no active applications")
                    return@addSyntax
                }

                var applicant: Resident = resident
                if (town.applications.size == 1) {
                    town.applications.forEach { k, v ->
                        applicant = k
                    }
                } else {
                    val applicantsString = town.applications.map { application -> application.key.name }.joinToString(", ")
                    Message.print(player, "There are multiple town applications. Please use \"/town accept [player]\".\nCurrent applicants: $applicantsString")
                    return@addSyntax
                }

                Message.print(player, "${applicant.name} has been accepted into your town!")
                val applicantPlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(applicant.name)
                if (applicantPlayer != null) {
                    Message.print(applicantPlayer, "You have been accepted into ${town.name}!")
                }

                Nodes.addResidentToTown(town, applicant)
                town.applications.remove(applicant)
            }
        })

        addSyntax({ player, resident, context ->
            val town = resident.town
            if (town == null) {
                if (resident.invitingTown == null) {
                    Message.error(player, "You have not been invited to any town or your invitation expired")
                    return@addSyntax
                }

                Message.print(player, "You are now a member of ${resident.invitingTown?.name}! Type \"/t spawn\" to teleport to your new town.")
                Message.print(resident.invitingPlayer, "${resident.name} has accepted your invitation!")

                Nodes.addResidentToTown(resident.invitingTown!!, resident)
                resident.invitingTown = null
                resident.invitingPlayer = null
                resident.inviteThread = null
            } else {
                if (town.leader != resident && !town.officers.contains(resident)) {
                    Message.error(player, "You aren't allowed to consider town applications")
                    return@addSyntax
                }

                if (town.applications.isEmpty()) {
                    Message.error(player, "There are no active applications")
                    return@addSyntax
                }

                var applicant: Resident = resident
                if (town.applications.size == 1) {
                    town.applications.forEach { k, v ->
                        applicant = k
                    }
                    if (context[playerArg].name != applicant.name) {
                        Message.error(player, "That player has not applied or their application has expired")
                        return@addSyntax
                    }
                } else {
                    applicant = context[playerArg]
                    if (!town.applications.containsKey(applicant)) {
                        Message.error(player, "That player has not applied or their application has expired")
                        return@addSyntax
                    }
                }

                Message.print(player, "${applicant.name} has been accepted into your town!")
                val applicantPlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(applicant.name)
                if (applicantPlayer != null) {
                    Message.print(applicantPlayer, "You have been accepted into ${town.name}!")
                }

                Nodes.addResidentToTown(town, applicant)
                town.applications.remove(applicant)
            }
        }, playerArg)
    }
}

class TownDenyCommand : Command("deny", "reject") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/town deny")
            Message.print(sender, "/town deny <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, context ->
            val town = resident.town
            if (town == null) {
                if (resident.invitingTown == null) {
                    Message.error(player, "You have not been invited to any town or your invitation expired")
                    return@addSyntax
                }

                Message.print(player, "You have rejected the invitation to join ${resident.invitingTown?.name}")
                Message.print(resident.invitingPlayer, "${resident.name} has rejected your invitation!")
                resident.invitingTown = null
                resident.invitingPlayer = null
                resident.inviteThread = null
            } else {
                if (town.leader != resident && !town.officers.contains(resident)) {
                    Message.error(player, "You aren't allowed to consider town applications")
                    return@addSyntax
                }

                if (town.applications.isEmpty()) {
                    Message.error(player, "There are no active applications")
                    return@addSyntax
                }

                var applicant: Resident = resident
                if (town.applications.size == 1) {
                    town.applications.forEach { k, v ->
                        applicant = k
                    }
                } else {
                    val applicantsString = town.applications.map { application -> application.key.name }.joinToString(", ")
                    Message.print(player, "There are multiple town applications. Please use \"/town accept [player]\".\nCurrent applicants: $applicantsString")
                    return@addSyntax
                }

                Message.print(player, "${applicant.name} has been denied residence in your town!")
                val applicantPlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(applicant.name)
                if (applicantPlayer != null) {
                    Message.print(applicantPlayer, "Your application to ${town.name} has been rejected!")
                }

                town.applications.remove(applicant)
            }
        })

        addSyntax({ player, resident, context ->
            val town = resident.town
            if (town == null) {
                if (resident.invitingTown == null) {
                    Message.error(player, "You have not been invited to any town or your invitation expired")
                    return@addSyntax
                }

                Message.print(player, "You have rejected the invitation to join ${resident.invitingTown?.name}")
                Message.print(resident.invitingPlayer, "${resident.name} has rejected your invitation!")
                resident.invitingTown = null
                resident.invitingPlayer = null
                resident.inviteThread = null
            } else {
                if (town.leader != resident && !town.officers.contains(resident)) {
                    Message.error(player, "You aren't allowed to consider town applications")
                    return@addSyntax
                }

                if (town.applications.isEmpty()) {
                    Message.error(player, "There are no active applications")
                    return@addSyntax
                }

                var applicant: Resident = resident
                if (town.applications.size == 1) {
                    town.applications.forEach { k, v ->
                        applicant = k
                    }
                    if (context[playerArg].name != applicant.name) {
                        Message.error(player, "That player has not applied or their application has expired")
                        return@addSyntax
                    }
                } else {
                    applicant = context[playerArg]
                    if (!town.applications.containsKey(applicant)) {
                        Message.error(player, "That player has not applied or their application has expired")
                        return@addSyntax
                    }
                }

                Message.print(player, "${applicant.name} has been denied residence in your town!")
                val applicantPlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(applicant.name)
                if (applicantPlayer != null) {
                    Message.print(applicantPlayer, "Your application to ${town.name} has been rejected!")
                }

                town.applications.remove(applicant)
            }
        }, playerArg)
    }
}

class TownLeaveCommand : Command("leave") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town leave")
        }

        addSyntax({ player, resident, town, context ->
            if (town.leader == resident) {
                Message.error(player, "You must transfer leadership before leaving the town")
                return@addSyntax
            }

            // do not allow during war?
            if (!Nodes.config.canLeaveTownDuringWar && Nodes.war.enabled) {
                Message.error(player, "Cannot leave your town during war")
                return@addSyntax
            }

            Message.print(player, "You have left ${town.name}")
            Nodes.removeResidentFromTown(town, resident)
        })
    }
}

class TownKickCommand : Command("kick") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town kick <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            // check if player is leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can kick players")
                return@addSyntax
            }

            // get other resident
            if (context[playerArg] === null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }

            val targetTown = context[playerArg].town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            // cannot kick leaders or officers
            if (context[playerArg] === leader || town.officers.contains(context[playerArg])) {
                Message.error(player, "You cannot kick the leader or other officers")
                return@addSyntax
            }

            Message.print(player, "You have kicked ${context[playerArg].name} from the town")

            val targetPlayer = context[playerArg].player()
            if (targetPlayer !== null) {
                Message.print(targetPlayer, "${ChatColor.DARK_RED}You have been kicked from ${town.name}")
            }

            Nodes.removeResidentFromTown(town, context[playerArg])
        }, playerArg)
    }
}

class TownSpawn : Command("spawn") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town spawn")
        }

        addSyntax({ player, resident, town, context ->
            // check if already trying to teleport
            if (resident.teleportThread !== null) {
                Message.error(player, "You are already trying to teleport")
                return@addSyntax
            }

            // ticks before teleport timer runs
            var teleportTime = Nodes.config.townSpawnTime.coerceAtLeast(0)

            // multiplier during war and if home occupied
            if (Nodes.war.enabled && Nodes.getTerritoryFromId(town.home)?.occupier !== null) {
                Message.error(player, "${ChatColor.BOLD}Your home is occupied, town spawn will take much longer...")
                teleportTime *= Nodes.config.occupiedHomeTeleportMultiplier
            }

            resident.teleportThread = MinecraftServer.getSchedulerManager().buildTask {
                player.teleport(town.spawnpoint)
                resident.teleportThread = null
            }
                .delay(TaskSchedule.millis(teleportTime))
                .schedule()

            if (teleportTime > 0) {
                val seconds = teleportTime / 1000

                Message.print(player, "Teleporting to town spawn in $seconds seconds. Don't move...")
            }
        })
    }
}

class TownSetSpawn : Command("setspawn") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town setspawn")
        }

        addSyntax({ player, resident, town, context ->
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "You are not a town leader or officer")
                return@addSyntax
            }

            val result = Nodes.setTownSpawn(town, player.position)

            if (result) {
                Message.print(player, "Town spawn set to current location")
            } else {
                Message.error(player, "Spawn location must be within town's home territory")
            }
        })
    }
}

class TownListCommand : Command("list") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town list")
        }

        addSyntax({ player, resident, context ->
            Message.print(player, "${ChatColor.BOLD}Town - Population")
            val townsList = ArrayList(Nodes.towns.values)
            townsList.sortByDescending { it.residents.size }
            townsList.forEach { town ->
                Message.print(player, "${town.name}${ChatColor.WHITE} - ${town.residents.size}")
            }
        })
    }
}

class TownInfoCommand : Command("info") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/town info")
            Message.print(sender, "/town info <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, town, context ->
            town.printInfo(player)
        })

        addSyntax({ player, resident, context ->
            context[townArg].printInfo(player)
        }, townArg)
    }
}

class TownOnlineCommand : Command("online") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/town online")
            Message.print(sender, "/town online <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, town, context ->
            val numPlayersOnline = town.playersOnline.size
            val playersOnline = town.playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in town ${town.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        })

        addSyntax({ player, resident, context ->
            val numPlayersOnline = context[townArg].playersOnline.size
            val playersOnline = context[townArg].playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in town ${context[townArg].name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        }, townArg)
    }
}

class TownIncomeCommand : Command("income") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town income")
        }

        addSyntax({ player, resident, town, context ->
            // check player permissions
            val hasPermissions = if (resident === town.leader || town.officers.contains(resident)) {
                true
            } else if (town.permissions[TownPermissions.INCOME].contains(PermissionsGroup.TOWN) && resident.town === town) {
                true
            } else if (town.permissions[TownPermissions.INCOME].contains(PermissionsGroup.TRUSTED) && resident.town === town && resident.trusted) {
                true
            } else {
                false
            }

            // open town inventory
            if (hasPermissions) {
                player.openInventory(Nodes.getTownIncomeInventory(town))
            } else {
                Message.error(player, "You do not have permissions to view town income")
            }
        })
    }
}

class TownMapCommand : Command("map") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town map")
        }

        addSyntax({ player, resident, context ->
            val loc = player.position
            val coordX = kotlin.math.floor(loc.x).toInt()
            val coordZ = kotlin.math.floor(loc.z).toInt()
            val coord = Coord.fromBlockCoords(coordX, coordZ)

            // minimap size
            val sizeY = 8
            val sizeX = 10

            Message.print(player, "\n${ChatColor.WHITE}--------------- Territory Map ---------------")
            for ((i, y) in (sizeY downTo -sizeY).withIndex()) {
                val renderedLine = WorldMap.renderLine(resident, coord, coord.z - y, coord.x - sizeX, coord.x + sizeX)
                Message.print(player, MAP_STR_BEGIN + renderedLine + MAP_STR_END[i])
            }
            Message.print(player, "")
        })
    }
}

class TownMinimapCommand : Command("minimap") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/town minimap")
            Message.print(sender, "/town minimap <size>")
        }

        val sizeArg = ArgumentType.Integer("size")

        addSyntax({ player, resident, context ->
            if (resident.minimap != null) {
                resident.destroyMinimap()
                Message.print(player, "Minimap disabled")
            } else {
                val size = 5
                resident.createMinimap(player, size)
                Message.print(player, "Minimap enabled (size = $size)")
            }
        })

        addSyntax({ player, resident, context ->
            // if size input, create new minimap of that size
            // note: minimap creation internally handles removing old minimaps
            val size = context[sizeArg].coerceIn(3, 5)
            resident.createMinimap(player, size)
            Message.print(player, "Minimap enabled (size = $size)")
        }, sizeArg)
    }
}

class TownPermissionsCommand : Command("permissions", "perms") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/town permissions")
            Message.print(sender, "/town permissions <type> <group> <flag>")
        }

        val typeArg = ArgumentType.Word("type").from("build", "destroy", "interact", "chests", "items", "income")
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
            if (resident === town.leader || town.officers.contains(resident)) {
                Message.print(player, "Usage:")
                Message.print(player, "/town permissions")
                Message.print(player, "/town permissions <type> <group> <flag>")
            }
        })

        addSyntax({ player, resident, town, context ->
            if (resident !== town.leader && !town.officers.contains(resident)) {
                Message.error(player, "Only the town leader or officers can do this")
                return@addSyntax
            }

            // match permissions and group
            val permissions: TownPermissions = when (context[typeArg].lowercase()) {
                "build" -> TownPermissions.BUILD
                "destroy" -> TownPermissions.DESTROY
                "interact" -> TownPermissions.INTERACT
                "chests" -> TownPermissions.CHESTS
                "items" -> TownPermissions.USE_ITEMS
                "income" -> TownPermissions.INCOME
                else -> {
                    Message.error(player, "Invalid permissions type ${context[typeArg]}. Valid options: build, destroy, interact, items, income")
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

            Nodes.setTownPermissions(town, permissions, group, flag)

            Message.print(player, "Set permissions for ${town.name}: $permissions $group $flag")
        }, typeArg, groupArg, flagArg)
    }
}

class TownProtectCommand : Command("protect") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "[Nodes] Town protect commands:")
            Message.print(sender, "/town protect${ChatColor.WHITE}: Toggle protecting chests")
            Message.print(sender, "/town protect show${ChatColor.WHITE}: Show protected chests")
        }

        addSyntax({ player, resident, town, context ->
            // check if player is town leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can protect chests")
                return@addSyntax
            }

            if (resident.isProtectingChests) {
                Nodes.stopProtectingChests(resident)
                Message.print(player, "${ChatColor.DARK_AQUA}Stopped protecting chests.")
            } else {
                Nodes.startProtectingChests(resident)
                Message.print(player, "Click on a chest to protect or unprotect it. Use \"/t protect\" again to stop protecting, or click a non-chest block to stop.")
            }
        })

        addSubcommand(TownProtectShowCommand())
    }
}

class TownProtectShowCommand : Command("show") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town protect show")
        }

        addSyntax({ player, resident, town, context ->
            Message.print(player, "Protected chests:")
            // print protected chests
            for (block in town.protectedBlocks) {
                Message.print(player, "${ChatColor.WHITE}${MinecraftServer.getInstanceManager().instances.first().getBlock(block).name()}: x: ${block.blockX}, y: ${block.blockY}, z: ${block.blockZ}")
            }

            Nodes.showProtectedChests(town, resident)
        })
    }
}

class TownTrustCommand : Command("trust") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town trust <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            // check if player is leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can trust/untrust players")
                return@addSyntax
            }

            val targetTown = context[playerArg].town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            // set player trust
            Nodes.setResidentTrust(context[playerArg], true)
            Message.print(player, "${context[playerArg].name} is now marked as trusted")
        }, playerArg)
    }
}

class TownUntrustCommand : Command("untrust") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town untrust <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            // check if player is leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can trust/untrust players")
                return@addSyntax
            }

            // get other resident
            if (context[playerArg] == null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }

            val targetTown = context[playerArg].town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            // set player trust
            Nodes.setResidentTrust(context[playerArg], false)
            Message.print(player, "${ChatColor.DARK_AQUA}${context[playerArg].name} is marked as untrusted")
        }, playerArg)
    }
}

class TownFlyCommand : Command("fly") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town fly")
        }

        addSyntax({ player, resident, town, context ->
            // do not allow during war
            if (Nodes.war.enabled) {
                Message.error(player, "Cannot fly during war")
                return@addSyntax
            }

            if (player.isAllowFlying) {
                player.isAllowFlying = false
                // give player slow falling to avoid fall damage
                player.addEffect(Potion(PotionEffect.SLOW_FALLING, 0, 100))
                Message.print(player, "Disabled flight")
                return@addSyntax
            }

            if (Nodes.getTerritoryFromPlayer(player)?.town != town) {
                Message.error(player, "You must be in your town to enable flight")
                return@addSyntax
            }

            player.isAllowFlying = true
            Message.print(player, "Enabled flight")
        })
    }
}
