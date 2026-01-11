/**
 * /nation (/n) command
 */

package luna.nodes.commands

//import org.bukkit.Bukkit
import org.bukkit.ChatColor
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
//import org.bukkit.command.CommandExecutor
//import org.bukkit.command.CommandSender
//import org.bukkit.command.TabCompleter
import net.minestom.server.entity.Player
//import org.bukkit.inventory.ItemStack
import luna.nodes.Config
import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.constants.ErrorNationExists
import luna.nodes.constants.ErrorPlayerHasNation
import luna.nodes.constants.ErrorTownHasNation
import luna.nodes.objects.Nation
import luna.nodes.utils.sanitizeString
//import luna.nodes.utils.string.filterByStart
//import luna.nodes.utils.string.filterNation
//import luna.nodes.utils.string.filterNationTown
//import luna.nodes.utils.string.filterTown
import luna.nodes.utils.stringInputIsValid
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule

//import java.util.concurrent.TimeUnit

class NationCommand : Command("n", "nation") {
    init {
        // no args, print current nation info
        setDefaultExecutor { sender, context ->
            val player = sender as? Player

            if (player != null) {
                // print player's nation info
                val resident = Nodes.getResident(player)
                if (resident != null && resident.nation != null) {
                    resident.nation!!.printInfo(player)
                }
                Message.print(player, "Use \"/nation help\" to view commands")
            }
        }

        addSubcommand(NationHelpCommand())
        addSubcommand(NationCreateCommand())
        addSubcommand(NationDeleteCommand())
        addSubcommand(NationLeaveCommand())
        addSubcommand(NationCapitalCommand())
        addSubcommand(NationInviteCommand())
        addSubcommand(NationAcceptCommand())
        addSubcommand(NationDenyCommand())
        addSubcommand(NationListCommand())
        addSubcommand(NationColorCommand())
        addSubcommand(NationRenameCommand())
        addSubcommand(NationOnlineCommand())
        addSubcommand(NationInfoCommand())
    }
}

class NationHelpCommand : Command("help") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Nation commands:")
            Message.print(sender, "/nation create${ChatColor.WHITE}: Create nation with name at location")
            Message.print(sender, "/nation delete${ChatColor.WHITE}: Delete your nation")
            Message.print(sender, "/nation leave${ChatColor.WHITE}: Leave your nation")
            Message.print(sender, "/nation invite${ChatColor.WHITE}: Invite a nation to your nation")
            Message.print(sender, "/nation list${ChatColor.WHITE}: List all nations")
            Message.print(sender, "/nation color${ChatColor.WHITE}: Set nation color on map")
        }
    }
}

class NationCreateCommand : Command("create", "new") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation create [name]")
        }

        var nameArg = ArgumentType.String("name")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // do not allow during war
            if (Nodes.war.enabled) {
                Message.error(player, "Cannot create nations during war")
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You need a town to form a nation")
                return@addSyntax
            }

            // only allow leaders to create nation
            if (resident !== town.leader) {
                Message.error(player, "Only the town leader can form a nation")
                return@addSyntax
            }

            val name = context[nameArg]
            if (!stringInputIsValid(name)) {
                Message.error(player, "Invalid nation name")
                return@addSyntax
            }

            val result = Nodes.createNation(sanitizeString(name), town, resident)
            if (result.isSuccess) {
                Message.broadcast("${ChatColor.BOLD}Nation $name has been formed by ${town.name}")
            } else {
                when (result.exceptionOrNull()) {
                    ErrorNationExists -> Message.error(player, "Nation \"${name}\" already exists")
                    ErrorTownHasNation -> Message.error(player, "You already belong to a nation")
                    ErrorPlayerHasNation -> Message.error(player, "You already belong to a nation")
                }
            }
        },nameArg)
    }
}

class NationDeleteCommand : Command("delete", "disband") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation delete")
        }

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // check if player is nation leader
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val nation = resident.nation
            if (nation == null) {
                Message.error(player, "You do not belong to a nation")
                return@addSyntax
            }

            val leader = nation.capital.leader
            if (resident !== leader) {
                Message.error(player, "You are not the nation leader")
                return@addSyntax
            }

            // do not allow during war
            if (Nodes.war.enabled) {
                Message.error(player, "Cannot delete your nation during war")
                return@addSyntax
            }

            Nodes.destroyNation(nation)
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nation ${nation.name} has been destroyed")
        })
    }
}

class NationLeaveCommand : Command("leave") {
    init {
        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            // check if player is nation leader
            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You do not belong to a town")
                return@addSyntax
            }

            val nation = town.nation
            if (nation == null) {
                Message.error(player, "You do not belong to a nation")
                return@addSyntax
            }

            if (town === nation.capital) {
                Message.error(player, "The nation's capital cannot leave (use /n delete)")
                return@addSyntax
            }

            val leader = town.leader
            if (resident !== leader) {
                Message.error(player, "You are not the town leader")
                return@addSyntax
            }

            // do not allow during war
            if (Nodes.war.enabled && !Config.canLeaveNationDuringWar) {
                Message.error(player, "Cannot leave your nation during war")
                return@addSyntax
            }

            // remove town
            Nodes.removeTownFromNation(nation, town)

            for (r in town.residents) {
                val p = r.player()
                if (p != null) {
                    Message.print(p, "${ChatColor.BOLD}${ChatColor.DARK_RED}Your town has left nation ${ChatColor.WHITE}${nation.name}")
                }
            }
        })
    }
}

class NationCapitalCommand : Command("capital") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation capital [town]")
        }

        var newCapitalArg = ArgumentType.String("town")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val nation = resident.nation
            if (nation == null) {
                Message.error(player, "You do not belong to a nation")
                return@addSyntax
            }

            val leader = nation.capital.leader
            if (resident !== leader) {
                Message.error(player, "Only nation leaders can change the capital town")
                return@addSyntax
            }

            val newCapital = Nodes.getTownFromName(context[newCapitalArg])
            if (newCapital === null) {
                Message.error(player, "That town does not exist")
                return@addSyntax
            }
            if (newCapital.nation !== nation) {
                Message.error(player, "That town does not belong to this nation")
                return@addSyntax
            }
            if (newCapital === nation.capital) {
                Message.error(player, "This town is already the nation capital")
                return@addSyntax
            }

            Nodes.setNationCapital(nation, newCapital)

            // broadcast message
            Message.broadcast("${ChatColor.BOLD}${newCapital.name} is now the capital of ${nation.name}")
        },newCapitalArg)
    }
}

class NationInviteCommand : Command("invite") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: ${ChatColor.WHITE}/nation invite [town]")
        }

        val townArg = ArgumentType.String("town")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val nation = resident.nation
            if (nation == null) {
                Message.error(player, "You do not belong to a nation")
                return@addSyntax
            }

            val leader = nation.capital.leader
            if (resident !== leader) {
                Message.error(player, "Only nation leaders can invite new towns")
                return@addSyntax
            }

            val inviteeTown = Nodes.getTownFromName(context[townArg])
            if (inviteeTown == null) {
                Message.error(player, "That town does not exist")
                return@addSyntax
            }
            if (inviteeTown.nation != null) {
                Message.error(player, "That town already belongs to another nation")
                return@addSyntax
            }

            val inviteeResident = inviteeTown.leader
            if (inviteeResident == null) {
                Message.error(player, "That town has no leader (?)")
                return@addSyntax
            }
            val invitee: Player? = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(inviteeResident.name)
            if (invitee == null) {
                Message.error(player, "That town's leader is not online")
                return@addSyntax
            }

            Message.print(player, "${inviteeTown.name} has been invited to your nation.")
            Message.print(invitee, "Your town has been invited to join the nation of ${nation.name} by ${player.username}. \nType \"/n accept\" to agree or \"/n reject\" to refuse the offer.")
            inviteeResident.invitingNation = nation
            inviteeResident.invitingTown = inviteeTown
            inviteeResident.invitingPlayer = player
            inviteeResident.inviteThread = MinecraftServer.getSchedulerManager()
                .buildTask {
                    if (inviteeResident.invitingPlayer == player) {
                        Message.print(player, "${invitee.username} didn't respond to your nation invitation!")
                        inviteeResident.invitingNation = null
                        inviteeResident.invitingTown = null
                        inviteeResident.invitingPlayer = null
                        inviteeResident.inviteThread = null
                    }
                }
                .delay(TaskSchedule.tick(1200))
                .schedule()
        }, townArg)
    }
}

class NationAcceptCommand : Command("accept") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation accept")
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

            if (resident.town != resident.invitingTown) {
                Message.error(player, "Invite invalid")
                return@addSyntax
            }

            if (resident.invitingNation == null) {
                Message.error(player, "You have not been invited to any nation")
                return@addSyntax
            }

            Message.print(player, "${resident.town?.name} is now a jurisdiction of ${resident.invitingNation?.name}!")
            Message.print(resident.invitingPlayer, "${resident.town?.name} has accepted your authority!")

            Nodes.addTownToNation(resident.invitingNation!!, resident.town!!)
            resident.invitingNation = null
            resident.invitingTown = null
            resident.invitingPlayer = null
            resident.inviteThread = null
        })
    }
}

class NationDenyCommand : Command("deny", "reject") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation deny")
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

            if (resident.invitingNation == null) {
                Message.error(player, "You have not been invited to any nation")
                return@addSyntax
            }

            Message.print(player, "You have rejected the invitation to ${resident.invitingNation?.name}!")
            Message.print(resident.invitingPlayer, "${resident.town?.name} has rejected your authority!")

            resident.invitingNation = null
            resident.invitingTown = null
            resident.invitingPlayer = null
            resident.inviteThread = null
        })
    }
}

class NationListCommand : Command("list") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation list [nation]")
        }

        addSyntax( { sender, context ->
            val player = sender as? Player

            Message.print(player, "${ChatColor.BOLD}Nation - Population - Towns")
            val nationsList = ArrayList(Nodes.nations.values)
            nationsList.sortByDescending { it.residents.size }
            for (n in nationsList) {
                val townsList = ArrayList(n.towns)
                townsList.sortByDescending { it.residents.size }
                var towns = ""
                for ((i, t) in townsList.withIndex()) {
                    towns += t.name
                    towns += " (${t.residents.size})"
                    if (i < n.towns.size - 1) {
                        towns += ", "
                    }
                }
                Message.print(player, "${n.name} ${ChatColor.WHITE}- ${n.residents.size} - $towns")
            }
        })
    }
}

class NationColorCommand : Command("color") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation color [r] [g] [b]")
        }

        var rArg = ArgumentType.Integer("r")
        var gArg = ArgumentType.Integer("g")
        var bArg = ArgumentType.Integer("b")

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

            val nation = resident.nation
            if (nation == null) {
                return@addSyntax
            }

            val leader = nation.capital.leader
            if (resident !== leader) {
                Message.error(player, "Only nation leaders can do this")
                return@addSyntax
            }

            // parse color
            val r = context[rArg].coerceIn(0, 255)
            val g = context[gArg].coerceIn(0, 255)
            val b = context[bArg].coerceIn(0, 255)

            Nodes.setNationColor(nation, r, g, b)
            Message.print(player, "Nation color set: ${ChatColor.WHITE}$r $g $b")
        },rArg,gArg,bArg)
    }
}

class NationRenameCommand : Command("rename") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation rename [new_name]")
        }

        var nameArg = ArgumentType.String("new_name")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            val nation = resident.nation
            if (nation == null) {
                Message.error(player, "You do not belong to a nation")
                return@addSyntax
            }

            if (resident !== nation.capital.leader) {
                Message.error(player, "Only nation leaders can do this")
                return@addSyntax
            }

            val name = context[nameArg]
            if (!stringInputIsValid(name)) {
                Message.error(player, "Invalid nation name")
                return@addSyntax
            }

            if (nation.name.lowercase() == context[nameArg].lowercase()) {
                Message.error(player, "Your nation is already named ${nation.name}")
                return@addSyntax
            }

            if (Nodes.nations.containsKey(name)) {
                Message.error(player, "There is already a nation with this name")
                return@addSyntax
            }

            Nodes.renameNation(nation, name)
            Message.print(player, "Nation renamed to ${nation.name}!")
        },nameArg)
    }
}

class NationOnlineCommand : Command("online") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation online [nation]")
        }

        var nationArg = ArgumentType.String("nation")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            var nation: Nation? = null
            if (resident.nation == null) {
                Message.error(player, "You do not belong to a nation")
                return@addSyntax
            }
            nation = resident.nation

            if (nation == null) {
                return@addSyntax
            }

            val numPlayersOnline = nation.playersOnline.size
            val playersOnline = nation.playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in nation ${nation.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
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

            var nation: Nation? = null
            if (!Nodes.nations.containsKey(context[nationArg])) {
                Message.error(player, "That nation does not exist")
                return@addSyntax
            }
            nation = Nodes.getNationFromName(context[nationArg])

            if (nation == null) {
                return@addSyntax
            }

            val numPlayersOnline = nation.playersOnline.size
            val playersOnline = nation.playersOnline.joinToString(", ", transform = { p -> p.username })
            Message.print(player, "Players online in nation ${nation.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
        }, nationArg)
    }
}

class NationInfoCommand : Command("info") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nation info [nation]")
        }

        var nationArg = ArgumentType.String("nation")

        addSyntax( { sender, context ->
            val player = sender as? Player

            if (player == null) {
                return@addSyntax
            }

            val resident = Nodes.getResident(player)
            if (resident == null) {
                return@addSyntax
            }

            var nation: Nation? = null
            if (resident.nation == null) {
                Message.error(player, "You do not belong to a nation")
                return@addSyntax
            }
            nation = resident.nation

            nation?.printInfo(player)
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

            var nation: Nation? = null
            if (!Nodes.nations.containsKey(context[nationArg])) {
                Message.error(player, "That nation does not exist")
                return@addSyntax
            }
            nation = Nodes.getNationFromName(context[nationArg])

            nation?.printInfo(player)
        }, nationArg)
    }
}