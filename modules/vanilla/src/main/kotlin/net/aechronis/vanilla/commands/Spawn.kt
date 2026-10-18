package net.aechronis.vanilla.commands

import net.aechronis.combat.objects.Vehicle
import net.aechronis.server.Server
import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Combat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player

class Spawn : Command("spawn") {
    init {
        setDefaultExecutor { player: Player, _ ->
            if (Combat.isInCombat(player)) {
                player.sendMessage(Component.text("You cannot use /spawn while in combat.", NamedTextColor.RED))
                return@setDefaultExecutor
            }
            Vehicle.exit(player)
            if (player.instance === Server.instance) {
                player.teleport(Server.spawnPoint)
            } else {
                player.setInstance(Server.instance, Server.spawnPoint)
            }
            player.sendMessage(Component.text("Teleported to spawn.", NamedTextColor.LIGHT_PURPLE))
        }
    }
}
