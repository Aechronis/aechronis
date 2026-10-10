package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.TownFly
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.FlagWar
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.timer.TaskSchedule

class TownSpawn : NodesCommand("spawn") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town spawn")
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
            if (FlagWar.enabled && Territory.fromId(town.home)?.occupier !== null) {
                Message.error(player, "${ChatColor.BOLD}Your home is occupied, town spawn will take much longer...")
                teleportTime *= Nodes.config.occupiedHomeTeleportMultiplier
            }

            resident.teleportThread = ModuleScheduler.buildTask {
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

class TownSetSpawn : NodesCommand("setspawn") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town setspawn")
        }

        addSyntax({ player, resident, town, context ->
            if (!requireTownStaff(player, resident, town, "You are not a town leader or officer")) return@addSyntax

            val result = Town.setSpawn(town, player.position)

            if (result) {
                Message.print(player, "Town spawn set to current location")
            } else {
                Message.error(player, "Spawn location must be within town's home territory")
            }
        })
    }
}

class TownFlyCommand : NodesCommand("fly", "nodes.fly") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town fly")
        }

        addSyntax({ player, resident, town, context ->
            // do not allow during war
            if (FlagWar.enabled) {
                Message.error(player, "Cannot fly during war")
                return@addSyntax
            }

            if (player.isAllowFlying) {
                TownFly.disable(player)
                Message.print(player, "Disabled flight")
                return@addSyntax
            }

            if (!TownFly.isAllowed(town, Territory.fromPlayer(player))) {
                Message.error(player, "You must be in your town or nation to enable flight")
                return@addSyntax
            }

            player.isAllowFlying = true
            Message.print(player, "Enabled flight")
        })
    }
}
