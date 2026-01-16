/**
 * /town (/s) command
 */

package luna.nodes.commands

//import org.bukkit.Bukkit
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import luna.nodes.utils.ChatColor
//import org.bukkit.command.Command
//import org.bukkit.command.CommandExecutor
//import org.bukkit.command.CommandSender
//import org.bukkit.command.TabCompleter
//import org.bukkit.entity.Player
//import org.bukkit.inventory.ItemStack
//import org.bukkit.potion.PotionEffect
//import org.bukkit.potion.PotionEffectType
import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.WorldMap
import luna.nodes.constants.ErrorPlayerHasTown
import luna.nodes.constants.ErrorTerritoryHasClaim
import luna.nodes.constants.ErrorTerritoryIsTownHome
import luna.nodes.constants.ErrorTerritoryNotConnected
import luna.nodes.constants.ErrorTerritoryNotInTown
import luna.nodes.constants.ErrorTerritoryOwned
import luna.nodes.constants.ErrorTownExists
import luna.nodes.constants.TownPermissions
//import luna.nodes.constants.NODES_SOUND_CHEST_PROTECT
import luna.nodes.constants.PermissionsGroup
//import luna.nodes.constants.TownPermissions
import luna.nodes.objects.Coord
import luna.nodes.objects.Resident
//import luna.nodes.objects.Resident
import luna.nodes.objects.Town
import luna.nodes.utils.sanitizeString
//import luna.nodes.utils.string.filterByStart
//import luna.nodes.utils.string.filterResident
//import luna.nodes.utils.string.filterTown
//import luna.nodes.utils.string.filterTownResident
import luna.nodes.utils.stringInputIsValid
import net.minestom.server.timer.TaskSchedule

//import java.util.concurrent.TimeUnit

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
        // no args, print current town info
        setDefaultExecutor { sender, context ->
            val player = sender as? Player

            if (player != null) {
                // print player's town info
                val resident = Nodes.getResident(player)
                if (resident != null && resident.town != null) {
                    resident.town!!.printInfo(player)
                }
                Message.print(player, "Use \"/town help\" to view commands")
            }
        }

        addSubcommand(TownHelpCommand())
        addSubcommand(TownCreateCommand())
        addSubcommand(TownDeleteCommand())
        addSubcommand(TownPromoteCommand())
        addSubcommand(TownDemoteCommand())
        addSubcommand(TownLeaderCommand())
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
        addSubcommand(TownColorCommand())
        addSubcommand(TownClaimCommand())
        addSubcommand(TownUnclaimCommand())
        addSubcommand(TownIncomeCommand())
        addSubcommand(TownRenameCommand())
        addSubcommand(TownMapCommand())
        addSubcommand(TownMinimapCommand())
        addSubcommand(TownPermissionsCommand())
        addSubcommand(TownTrustCommand())
        addSubcommand(TownUntrustCommand())
        addSubcommand(TownCapitalCommand())
        addSubcommand(TownAnnexCommand())
        addSubcommand(TownFlyCommand())
    }
}

class TownHelpCommand : Command("help") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Town commands:")
            Message.print(sender, "/town create${ChatColor.WHITE}: Create town with name at location")
            Message.print(sender, "/town delete${ChatColor.WHITE}: Delete your town")
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
            Message.print(sender, "/town color${ChatColor.WHITE}: Set town color on map")
            Message.print(sender, "/town claim${ChatColor.WHITE}: Claim territory at current location")
            Message.print(sender, "/town unclaim${ChatColor.WHITE}: Unclaim territory at current location")
            Message.print(sender, "/town rename${ChatColor.WHITE}: Rename town")
            Message.print(sender, "/town map${ChatColor.WHITE}: View world map")
            Message.print(sender, "/town minimap${ChatColor.WHITE}: Toggle sidebar world minimap")
            Message.print(sender, "/town permissions${ChatColor.WHITE}: Set town protection permissions")
            Message.print(sender, "/town protect${ChatColor.WHITE}: Protect town chests")
            Message.print(sender, "/town trust${ChatColor.WHITE}: Mark player as trusted")
            Message.print(sender, "/town untrust${ChatColor.WHITE}: Remove player from trusted")
            Message.print(sender, "/town capital${ChatColor.WHITE}: Set your town's home territory")
            Message.print(sender, "/town annex${ChatColor.WHITE}: Annex an occupied territory")
            Message.print(sender, "/town fly${ChatColor.WHITE}: Fly inside your town")
        }
    }
}

class TownCreateCommand : Command("create", "new") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: ${ChatColor.WHITE}/town create [name]")
        }

        var nameArg = ArgumentType.String("name")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // do not allow during war
            if (!Nodes.config.canCreateTownDuringWar && Nodes.war.enabled) {
                Message.error(player, "Cannot create towns during war")
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            // check if player has cooldown
            if (resident.townCreateCooldown > 0) {
                val remainingTime = resident.townCreateCooldown
                val remainingTimeString = run {
                    val hour: Long = remainingTime / 3600000L
                    val min: Long = 1L + (remainingTime - hour * 3600000L) / 60000L
                    "${hour}hr ${min}min"
                }

                Message.error(player, "You cannot create another town for: $remainingTimeString ")
                return@addSyntax
            }

            val name = context[nameArg]
            if (!stringInputIsValid(name)) {
                Message.error(player, "Invalid town name")
                return@addSyntax
            }

            val territory = Nodes.getTerritoryFromPlayer(player)
            if (territory == null) {
                Message.error(player, "This chunk has no territory")
                return@addSyntax
            }

            val result = Nodes.createTown(sanitizeString(name), territory, resident)
            if (result.isSuccess) {
                Message.broadcast("${ChatColor.BOLD}${player.username} has created the town \"${name}\"")
            } else {
                when (result.exceptionOrNull()) {
                    ErrorTownExists -> Message.error(player, "Town \"${name}\" already exists")
                    ErrorPlayerHasTown -> Message.error(player, "You already belong to a town")
                    ErrorTerritoryOwned -> Message.error(player, "Territory is already claimed by a town")
                }
            }

        }, nameArg)
    }
}

class TownDeleteCommand : Command("delete", "disband") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: ${ChatColor.WHITE}/town delete")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // check if player is town leader
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            val leader = town.leader
            if (resident !== leader) {
                Message.error(player, "You are not the town leader")
                return@addSyntax
            }

            val nation = town.nation
            if (nation !== null && town === nation.capital) {
                Message.error(player, "You cannot destroy your town as the nation capital, use /n delete first")
                return@addSyntax
            }

            // do not allow during war
            if (!Nodes.config.canDestroyTownDuringWar && Nodes.war.enabled) {
                Message.error(player, "Cannot delete your town during war")
                return@addSyntax
            }

            Nodes.destroyTown(town)

            // add player penalty for destroying town
            Nodes.setResidentTownCreateCooldown(resident, Nodes.config.townCreateCooldown)

            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}The town \"${town.name}\" has been destroyed...")
        })
    }
}

class TownPromoteCommand : Command("promote", "officer") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t promote [player]")
        }

        var targetArg = ArgumentType.String("player")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // check if player is town leader
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            val leader = town.leader
            if (resident !== leader) {
                Message.error(player, "You are not the town leader")
                return@addSyntax
            }

            // get other resident
            val target = Nodes.getResidentFromName(context[targetArg])
            if (target === null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }
            if (target === resident) {
                Message.error(player, "You are already the town leader")
                return@addSyntax
            }

            val targetTown = target.town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            val targetPlayer = target.player()

            // add officer
            if (!town.officers.contains(target)) {
                Nodes.townAddOfficer(town, target)
                Message.print(player, "Made ${target.name} a town officer")

                if (targetPlayer !== null) {
                    Message.print(targetPlayer, "You are now a town officer")
                }
            }
        }, targetArg)
    }
}

class TownDemoteCommand : Command("demote") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t demote [player]")
        }

        var targetArg = ArgumentType.String("player")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // check if player is town leader
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            val leader = town.leader
            if (resident !== leader) {
                Message.error(player, "You are not the town leader")
                return@addSyntax
            }

            // get other resident
            val target = Nodes.getResidentFromName(context[targetArg])
            if (target === null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }
            if (target === resident) {
                Message.error(player, "You are already the town leader")
                return@addSyntax
            }

            val targetTown = target.town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            val targetPlayer = target.player()

            // remove officer
            if (town.officers.contains(target)) {
                Nodes.townRemoveOfficer(town, target)
                Message.print(player, "Removed ${target.name} from town officers")

                if (targetPlayer !== null) {
                    Message.error(targetPlayer, "You are no longer a town officer")
                }
            }
        }, targetArg)
    }
}

class TownLeaderCommand : Command("leader") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t leader [player]")
        }

        var targetArg = ArgumentType.String("player")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // check if player is town leader
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            val leader = town.leader
            if (resident !== leader) {
                Message.error(player, "You are not the town leader")
                return@addSyntax
            }

            // get other resident
            val target = Nodes.getResidentFromName(context[targetArg])
            if (target === null) {
                Message.error(player, "This player does not exist")
                return@addSyntax
            }
            if (target === resident) {
                Message.error(player, "You are already the town leader")
                return@addSyntax
            }

            val targetTown = target.town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            Nodes.townSetLeader(town, target)
            Message.print(player, "You made ${target.name} the new leader of ${town.name}")

            val targetPlayer = target.player()
            if (targetPlayer !== null) {
                Message.print(targetPlayer, "You are now the leader of ${town.name}")
            }
        }, targetArg)
    }
}

class TownApplyCommand: Command("apply", "join") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town apply [town]")
        }

        val townArg = ArgumentType.String("town")

        addSyntax( {sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            if (resident.town != null) {
                Message.error(player, "You are already a member of a town")
                return@addSyntax
            }

            val town = Nodes.getTownFromName(context[townArg])
            if (town == null) {
                Message.error(player, "That town does not exist")
                return@addSyntax
            }

            if (town.isOpen) {
                Nodes.addResidentToTown(town, resident)
                Message.print(player, "You are now a resident of ${town.name}!")
                return@addSyntax
            }

            if (town.applications.containsKey(resident)) {
                Message.error(player, "You have already applied to ${town.name}")
                return@addSyntax
            }

            val approvers: ArrayList<Player> = ArrayList()
            MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(town.leader!!.name)?.let { player ->
                approvers.add(player)
            }
            town.officers.forEach { officer ->
                MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(officer.name)?.let { player ->
                    approvers.add(player)
                }
            }

            if (approvers.isEmpty()) {
                Message.error(player, "There are no officers online from ${town.name} to receive your application")
                return@addSyntax
            }

            approvers.forEach { approver ->
                Message.print(approver, "${resident.name} has applied to join to your town. \nType \"/t accept\" to let them in or \"/t reject\" to refuse the offer.")
            }
            Message.print(player, "Your application has been sent")

            town.applications.put(
                resident,
                MinecraftServer.getSchedulerManager()
                    .buildTask {
                        if (resident.town == null) {
                            player.sendMessage("No one in ${town.name} responded to your application!")
                            town.applications.remove(resident)
                        }
                    }
                    .delay(TaskSchedule.tick(1200))
                    .schedule()
            )
        }, townArg)
    }
}

class TownInviteCommand : Command("invite") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /town invite [player]")
        }

        val inviteeArg = ArgumentType.String("player")

        addSyntax( {sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }
            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            val invitee: Player? = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context[inviteeArg])
            if (invitee == null) {
                Message.error(player, "That player is not online")
                return@addSyntax
            } else if (invitee == player) {
                Message.error(player, "You're already in your town")
                return@addSyntax
            }

            val inviteeResident = Nodes.getResident(invitee)
            if (inviteeResident == null) {
                return@addSyntax
            }
            if (inviteeResident.invitingTown == town) {
                Message.error(player, "This player has already been invited to the town")
                return@addSyntax
            } else if (inviteeResident.invitingTown != null) {
                Message.error(player, "This player is considering another town invitation")
                return@addSyntax
            }
            val inviteeTown = inviteeResident.town
            if (inviteeTown != null) {
                Message.error(player, "This player is already a member of a town")
                return@addSyntax
            }

            if (town.leader === resident || town.officers.contains(resident)) {
                Message.print(player, "${invitee.username} has been invited to your town.")
                Message.print(invitee, "You have been invited to become a member of ${town.name}.\nType \"/t accept\" to join the town or \"/t reject\" to refuse the offer.")
                inviteeResident.invitingTown = town
                inviteeResident.invitingPlayer = player
                inviteeResident.inviteThread = MinecraftServer.getSchedulerManager()
                    .buildTask {
                        if (inviteeResident.invitingPlayer == player) {
                            Message.print(player, "${invitee.username} didn't respond to your town invitation!")
                            inviteeResident.invitingTown = null
                            inviteeResident.invitingPlayer = null
                            inviteeResident.inviteThread = null
                        }
                    }
                    .delay(TaskSchedule.tick(1200))
                    .schedule()
            } else {
                Message.error(player, "You are not allowed to invite new members")
            }

        }, inviteeArg)
    }
}

class TownAcceptCommand : Command("accept") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t accept [player]")
        }

        val applicantArg = ArgumentType.String("player")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

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

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

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
                    if (context[applicantArg].lowercase() != applicant.name.lowercase()) {
                        Message.error(player, "That player has not applied or their application has expired")
                        return@addSyntax
                    }
                } else {
                    applicant = Nodes.getResidentFromName(context[applicantArg])!!
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
        }, applicantArg)
    }
}

class TownDenyCommand : Command("deny", "reject") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t deny [player]")
        }

        val applicantArg = ArgumentType.String("player")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

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

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

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
                    if (context[applicantArg] != applicant.name) {
                        Message.error(player, "That player has not applied or their application has expired")
                        return@addSyntax
                    }
                } else {
                    applicant = Nodes.getResidentFromName(context[applicantArg])!!
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
        }, applicantArg)
    }
}

class TownLeaveCommand : Command("leave") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t leave")
        }

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

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
            Message.error(sender, "Usage: /t kick [player]")
        }

        val targetArg = ArgumentType.String("name")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player === null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident === null) {
                return@addSyntax
            }

            val town = resident.town
            if (town === null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            // check if player is leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can kick players")
                return@addSyntax
            }

            // get other resident
            val target = Nodes.getResidentFromName(context[targetArg])
            if (target === null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }

            val targetTown = target.town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            // cannot kick leaders or officers
            if (target === leader || town.officers.contains(target)) {
                Message.error(player, "You cannot kick the leader or other officers")
                return@addSyntax
            }

            Message.print(player, "You have kicked ${target.name} from the town")

            val targetPlayer = target.player()
            if (targetPlayer !== null) {
                Message.print(targetPlayer, "${ChatColor.DARK_RED}You have been kicked from ${town.name}")
            }

            Nodes.removeResidentFromTown(town, target)
        }, targetArg)
    }
}

class TownSpawn : Command("spawn") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t spawn")
        }

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player === null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident === null) {
                return@addSyntax
            }

            val town = resident.town
            if (town === null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            // check if already trying to teleport
            if (resident.teleportThread !== null) {
                Message.error(player, "You are already trying to teleport")
                return@addSyntax
            }

            // ticks before teleport timer runs
            var teleportTimerTicks = (Nodes.config.townSpawnTime * 20).coerceAtLeast(0)

            // multiplier during war and if home occupied
            if (Nodes.war.enabled && Nodes.getTerritoryFromId(town.home)?.occupier !== null) {
                Message.error(player, "${ChatColor.BOLD}Your home is occupied, town spawn will take much longer...")
                teleportTimerTicks *= Nodes.config.occupiedHomeTeleportMultiplier
            }

            resident.teleportThread = MinecraftServer.getSchedulerManager().buildTask {
                player.teleport(town.spawnpoint)
                resident.teleportThread = null
            }
                .delay(TaskSchedule.tick(teleportTimerTicks))
                .schedule()

            if (teleportTimerTicks > 0) {
                val seconds = teleportTimerTicks / 20

                Message.print(player, "Teleporting to town spawn in $seconds seconds. Don't move...")
            }
        })
    }
}

class TownSetSpawn : Command("setspawn") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t setspawn")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

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
            Message.error(sender, "Usage: /t list")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

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
            Message.error(sender, "Usage: /t info [town]")
        }

        val townArg = ArgumentType.String("town")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            var town: Town? = null
            if (resident.town == null) {
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }
            town = resident.town

            town?.printInfo(player)
        })

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            if (!Nodes.towns.containsKey(context[townArg])) {
                Message.error(player, "That town does not exist")
                return@addSyntax
            }
            var town = Nodes.getTownFromName(context[townArg])

            town?.printInfo(player)
        }, townArg)
    }
}

class TownOnlineCommand : Command("online") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t online [town]")
        }

        val townArg = ArgumentType.String("town")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            if (resident.town == null) {
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }
            var town = resident.town

            if (town == null) {
                return@addSyntax
            }

            val numPlayersOnline = town.playersOnline.size
            val playersOnline = town.playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in town ${town.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        })

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            if (!Nodes.towns.containsKey(context[townArg])) {
                Message.error(player, "That town does not exist")
                return@addSyntax
            }
            var town = Nodes.getTownFromName(context[townArg])

            if (town == null) {
                return@addSyntax
            }

            val numPlayersOnline = town.playersOnline.size
            val playersOnline = town.playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in town ${town.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        }, townArg)
    }
}

class TownColorCommand : Command("color") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t color [r] [g] [b]")
        }

        val rArg = ArgumentType.Integer("r")
        val gArg = ArgumentType.Integer("g")
        val bArg = ArgumentType.Integer("b")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // check if player is town leader
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                return@addSyntax
            }

            val leader = town.leader
            if (resident !== leader) {
                Message.error(player, "Only town leaders can do this")
                return@addSyntax
            }

            // parse color
            val r = context[rArg].coerceIn(0,255)
            val g = context[gArg].coerceIn(0, 255)
            val b = context[bArg].coerceIn(0, 255)

            Nodes.setTownColor(town, r, g, b)
            Message.print(player, "Town color set: ${ChatColor.WHITE}$r $g $b")
        }, rArg,gArg,bArg)
    }
}

class TownClaimCommand : Command("claim") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t claim")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player
            if (player == null) {
                return@addSyntax
            }

            // get town from player
            val resident = Nodes.getResident(player)
            val town = resident?.town
            if (town == null) {
                Message.error(player, "Cannot claim without being in a town")
                return@addSyntax
            }

            if (resident !== town.leader && !town.officers.contains(resident)) {
                Message.error(player, "You are not a town leader or officer")
                return@addSyntax
            }

            // get territory from chunk and run claim process
            val loc = player.position
            val territory = Nodes.getTerritoryFromBlock(loc.x.toInt(), loc.z.toInt())
            if (territory == null) {
                Message.error(player, "This chunk has no territory")
                return@addSyntax
            }

            val result = Nodes.claimTerritory(town, territory)
            if (result.isSuccess) {
                Message.print(player, "Territory(id=${territory.id}) claimed")
            } else {
                when (result.exceptionOrNull()) {
                    ErrorTerritoryNotConnected -> Message.error(player, "Territory must neighbor existing claims")
                    ErrorTerritoryHasClaim -> Message.error(player, "Territory is already claimed by a town")
                }
            }
        })
    }
}

class TownUnclaimCommand : Command("unclaim") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t unclaim")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // get town from player
            val resident = Nodes.getResident(player)
            val town = resident?.town
            if (town == null) {
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }

            if (resident !== town.leader && !town.officers.contains(resident)) {
                Message.error(player, "You are not a town leader or officer")
                return@addSyntax
            }

            // get territory from chunk and run claim process
            val loc = player.position
            val territory = Nodes.getTerritoryFromBlock(loc.x.toInt(), loc.z.toInt())
            if (territory == null) {
                Message.error(player, "This chunk has no territory")
                return@addSyntax
            }

            val result = Nodes.unclaimTerritory(town, territory)
            if (result.isSuccess) {
                Message.print(player, "Territory(id=${territory.id}) unclaimed")
            } else {
                when (result.exceptionOrNull()) {
                    ErrorTerritoryNotInTown -> Message.error(player, "Territory not owned by town")
                    ErrorTerritoryIsTownHome -> Message.error(player, "Cannot unclaim home territory")
                }
            }
        })
    }
}

class TownIncomeCommand : Command("income") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t income")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if ( player == null ) {
                return@addSyntax
            }

            // get town from player
            val resident = Nodes.getResident(player)
            val town = resident?.town
            if ( town == null ) {
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }

            // check player permissions
            val hasPermissions = if ( resident === town.leader || town.officers.contains(resident) ) {
                true
            }
            else if ( town.permissions[TownPermissions.INCOME].contains(PermissionsGroup.TOWN) && resident.town === town ) {
                true
            }
            else if ( town.permissions[TownPermissions.INCOME].contains(PermissionsGroup.TRUSTED) && resident.town === town && resident.trusted ) {
                true
            }
            else {
                false
            }

            // open town inventory
            if ( hasPermissions ) {
                player.openInventory(Nodes.getTownIncomeInventory(town))
            }
            else {
                Message.error(player, "You do not have permissions to view town income")
            }
        })
    }
}

class TownRenameCommand : Command("rename") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t rename [new_name]")
        }

        val nameArg = ArgumentType.String("new_name")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }

            if (resident != town.leader) {
                Message.error(player, "Only town leaders can do this")
                return@addSyntax
            }

            val name = context[nameArg]
            if (!stringInputIsValid(name)) {
                Message.error(player, "Invalid town name")
                return@addSyntax
            }

            if (town.name.lowercase() == name.lowercase()) {
                Message.error(player, "Your town is already named ${town.name}")
                return@addSyntax
            }

            if (Nodes.towns.containsKey(name)) {
                Message.error(player, "There is already a town with this name")
                return@addSyntax
            }

            Nodes.renameTown(town, name)
            Message.print(player, "Town renamed to ${town.name}!")
        }, nameArg)
    }
}

class TownMapCommand : Command("map") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t map")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

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
            Message.error(sender, "Usage: /t minimap [3|4|5]")
        }

        val sizeArg = ArgumentType.Integer("size")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            if (resident.minimap != null) {
                resident.destroyMinimap()
                Message.print(player, "Minimap disabled")
            } else {
                val size = 5
                resident.createMinimap(player, size)
                Message.print(player, "Minimap enabled (size = $size)")
            }
        })

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

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
            Message.error(sender, "Usage: /town permissions [type] [group] [allow/deny]")
            Message.error(sender, "[type]: build, destroy, interact, chests, items, income")
            Message.error(sender, "[group]: town, nation, ally, outsider, trusted")
            Message.error(sender, "[allow/deny]: either \"allow\" or \"deny\"")
        }

        val typeArg = ArgumentType.String("type")
        val groupArg = ArgumentType.String("group")
        val flagArg = ArgumentType.String("flag")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }

            // print current town permissions
            Message.print(sender, "Town Permissions:")
            for (perm in enumValues<TownPermissions>()) {
                val groups = town.permissions[perm]
                Message.print(player, "- ${perm}${ChatColor.WHITE}: $groups")
            }

            // print usage for leader, officers
            if (resident === town.leader || town.officers.contains(resident)) {
                Message.error(player, "Usage: /town permissions [type] [group] [allow/deny]")
                Message.error(player, "[type]: build, destroy, interact, chests, items, income")
                Message.error(player, "[group]: town, nation, ally, outsider, trusted")
                Message.error(player, "[allow/deny]: either \"allow\" or \"deny\"")
            }
        })

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }

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

class TownTrustCommand : Command("trust") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t trust [player]")
        }

        val targetArg = ArgumentType.String("player")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            // check if player is leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can trust/untrust players")
                return@addSyntax
            }

            // get other resident
            val target = Nodes.getResidentFromName(context[targetArg])
            if (target == null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }

            val targetTown = target.town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            // set player trust
            Nodes.setResidentTrust(target, true)
            Message.print(player, "${target.name} is now marked as trusted")
        }, targetArg)
    }
}

class TownUntrustCommand : Command("untrust") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t untrust [player]")
        }

        val targetArg = ArgumentType.String("player")

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            // check if player is leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can trust/untrust players")
                return@addSyntax
            }

            // get other resident
            val target = Nodes.getResidentFromName(context[targetArg])
            if (target == null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }

            val targetTown = target.town
            if (targetTown !== town) {
                Message.error(player, "Player is not in this town")
                return@addSyntax
            }

            // set player trust
            Nodes.setResidentTrust(target, false)
            Message.print(player, "${ChatColor.DARK_AQUA}${target.name} is marked as untrusted")
        }, targetArg)
    }
}

class TownCapitalCommand : Command("capital") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t capital")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            // check if player is leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can move the town's home capital territory")
                return@addSyntax
            }

            // check if territory belongs to town and isnt home already
            val territory = Nodes.getTerritoryFromPlayer(player)
            if (territory == null) {
                Message.error(player, "This region has no territory")
                return@addSyntax
            }
            if (town !== territory.town) {
                Message.error(player, "This is not your territory")
                return@addSyntax
            }
            if (town.home == territory.id) {
                Message.error(player, "This is already your home territory")
                return@addSyntax
            }
            if (town.moveHomeCooldown > 0) {
                val remainingTime = town.moveHomeCooldown
                val remainingTimeString = run {
                    val hour: Long = remainingTime / 3600000L
                    val min: Long = 1L + (remainingTime - hour * 3600000L) / 60000L
                    "${hour}hr ${min}min"
                }

                Message.error(player, "You cannot move the town's home territory for: $remainingTimeString ")
                return@addSyntax
            }

            // move home territory
            Nodes.setTownHomeTerritory(town, territory)
            Message.print(player, "You have moved the town's home territory to id = ${territory.id} (do not forget to update /t setspawn)")
        })
    }
}

class TownAnnexCommand : Command("annex") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t annex")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            if (Nodes.config.annexDisabled) {
                Message.error(player, "Annexing disabled")
                return@addSyntax
            }

            if (!Nodes.war.enabled || !Nodes.war.canAnnexTerritories) {
                Message.error(player, "You can only annex territories during war")
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

            // check if player is leader or officer
            val leader = town.leader
            if (resident !== leader && !town.officers.contains(resident)) {
                Message.error(player, "Only leaders and officers can annex territories")
                return@addSyntax
            }

            // check if territory belongs to town and isnt home already
            val territory = Nodes.getTerritoryFromPlayer(player)
            if (territory == null) {
                Message.error(player, "This region has no territory")
                return@addSyntax
            }

            val territoryTown = territory.town
            if (territoryTown === null) {
                Message.error(player, "There is no town here")
                return@addSyntax
            }

            // check blacklist
            if (Nodes.config.warUseBlacklist && Nodes.config.warBlacklist.contains(territoryTown.uuid)) {
                Message.error(player, "Cannot annex this town (blacklisted)")
                return@addSyntax
            }
            if (Nodes.config.useAnnexBlacklist && Nodes.config.annexBlacklist.contains(territoryTown.uuid)) {
                Message.error(player, "Cannot annex this town (blacklisted)")
                return@addSyntax
            }

            // check whitelist
            if (Nodes.config.warUseWhitelist) {
                if (!Nodes.config.warWhitelist.contains(territoryTown.uuid)) {
                    Message.error(player, "Cannot annex this town (not whitelisted)")
                    return@addSyntax
                } else if (Nodes.config.onlyWhitelistCanAnnex && !Nodes.config.warWhitelist.contains(town.uuid)) {
                    Message.error(player, "Cannot annex territories because your town is not white listed")
                    return@addSyntax
                }
            }

            if (town === territoryTown) {
                Message.error(player, "This already your territory")
                return@addSyntax
            }
            if (territory.occupier !== town) {
                Message.error(player, "You have not occupied this territory")
                return@addSyntax
            }
            if (territoryTown.home == territory.id && territoryTown.territories.size > 1) {
                Message.error(player, "You must annex all of this town's other territories before you can annex its home territory")
                return@addSyntax
            }

            val result = Nodes.annexTerritory(town, territory)
            if (result) {
                Message.print(player, "Annexed territory (id = ${territory.id})")
            } else {
                Message.error(player, "Failed to annex territory")
            }
        })
    }
}

class TownFlyCommand : Command("fly") {
    init {
        setDefaultExecutor { sender, context ->
            Message.error(sender, "Usage: /t fly")
        }

        addSyntax({ sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You are not a member of a town")
                return@addSyntax
            }

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

            if (Nodes.getTerritoryFromChunk(player.chunk!!)?.town != town) {
                Message.error(player, "You must be in your town to enable flight")
                return@addSyntax
            }

            player.isAllowFlying = true
            Message.print(player, "Enabled flight")
        })
    }
}