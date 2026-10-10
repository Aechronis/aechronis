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
        setDefaultExecutor { player, resident, context ->
            FlagWar.printInfo(player, true)
            Message.print(player, "Toggle state: \"/nodesadmin war [enable|disable|skirmish|deathwar]\"")
        }

        addSubcommand(NodesAdminWarEnableCommand())
        addSubcommand(NodesAdminWarDisableCommand())
        addSubcommand(NodesAdminWarSkirmishCommand())
        addSubcommand(NodesAdminWarDeathWarCommand())
    }
}

class NodesAdminWarEnableCommand : NodesCommand("enable", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war enable")
        }

        addSyntax({ player, resident, context ->
            FlagWar.enable(canAnnexTerritories = true, canOnlyAttackBorders = false, destructionEnabled = true)
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")

            // play MENACING wither spawn sound
            Audiences.all().playSound(Sound.sound(Key.key("entity.wither.spawn"), Sound.Source.PLAYER, 1.0f, 1.0f))
        })
    }
}

class NodesAdminWarDeathWarCommand : NodesCommand("deathwar", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war deathwar")
        }

        addSyntax({ player, resident, context ->
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
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war disable")
        }

        addSyntax({ player, resident, context ->
            if (FlagWar.enabled) {
                FlagWar.disable()
                Message.broadcast("${ChatColor.BOLD}Nodes war disabled")
            } else {
                Message.error(player, "Nodes war already disabled")
            }
        })
    }
}

class NodesAdminWarSkirmishCommand : NodesCommand("skirmish", "nodes.admin") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nodesadmin war skirmish")
        }

        addSyntax({ player, resident, context ->
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
