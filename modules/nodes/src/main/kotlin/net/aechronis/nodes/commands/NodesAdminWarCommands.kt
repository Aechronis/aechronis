package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.FlagWar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.minestom.server.adventure.audience.Audiences

class NodesAdminWarCommand : NodesCommand("war", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            FlagWar.printInfo(sender, true)
            Message.print(sender, "Toggle state: \"/nodesadmin war [enable|disable|skirmish|deathwar]\"")
            Message.print(sender, "Release all war occupations: \"/nodesadmin war releaseall\"")
        }

        addSubcommand(NodesAdminWarEnableCommand())
        addSubcommand(NodesAdminWarDisableCommand())
        addSubcommand(NodesAdminWarSkirmishCommand())
        addSubcommand(NodesAdminWarDeathWarCommand())
        addSubcommand(NodesAdminWarReleaseAllCommand())
    }
}

class NodesAdminWarReleaseAllCommand : NodesCommand("releaseall", "nodes.admin") {
    init {
        addSenderSyntax({ sender, context ->
            // A running war tracks defeats and owed lives against occupied homes; releasing them mid-war would desync that.
            if (FlagWar.enabled) {
                Message.error(sender, "Disable the war first")
                return@addSenderSyntax
            }
            val released = FlagWar.releaseWarOccupations()
            Message.print(sender, "Released $released occupied territories (colonies and warzones kept)")
        })
    }
}

class NodesAdminWarEnableCommand : NodesCommand("enable", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin war enable")
        }

        addSenderSyntax({ sender, context ->
            FlagWar.enable(canAnnexTerritories = true, canOnlyAttackBorders = false, destructionEnabled = true)
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")

            // play MENACING wither spawn sound
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}

class NodesAdminWarDeathWarCommand : NodesCommand("deathwar", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin war deathwar")
        }

        addSenderSyntax({ sender, context ->
            FlagWar.enable(
                canAnnexTerritories = true,
                canOnlyAttackBorders = false,
                destructionEnabled = true,
                deathWar = true,
            )
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes death war enabled")
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}

class NodesAdminWarDisableCommand : NodesCommand("disable", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin war disable")
        }

        addSenderSyntax({ sender, context ->
            if (FlagWar.enabled) {
                FlagWar.disable()
                Message.broadcast("${ChatColor.BOLD}Nodes war disabled")
            } else {
                Message.error(sender, "Nodes war already disabled")
            }
        })
    }
}

class NodesAdminWarSkirmishCommand : NodesCommand("skirmish", "nodes.admin") {
    init {
        setSenderDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nodesadmin war skirmish")
        }

        addSenderSyntax({ sender, context ->
            FlagWar.enable(
                canAnnexTerritories = false,
                canOnlyAttackBorders = true,
                destructionEnabled = Nodes.config.allowDestructionDuringSkirmish,
            )
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishes enabled")

            // play MENACING wither spawn sound
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}
