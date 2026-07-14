/**
 * Admin commands to manage combat
 * - give guns, ammo
 * - debug models
 *
 *    /combat command ...
 *    /nda command
 */

package net.aechronis.combat.commands

import net.aechronis.combat.commands.arguments.ArgumentHat
import net.aechronis.combat.commands.arguments.ArgumentItem
import net.aechronis.combat.objects.Explosion
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.storage.HatCollection
import net.aechronis.combat.utils.Message
import net.aechronis.utils.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.arguments.minecraft.ArgumentEntity
import net.minestom.server.entity.Player

class CombatAdminCommand : Command("combatadmin", "combat.admin", "ca") {
    init {
        setDefaultExecutor { sender, _ ->
            Message.print(sender, "[Combat] Admin commands:")
            Message.print(sender, "/combatadmin give: Give yourself various items")
            Message.print(sender, "/combatadmin explosion: Make an explosion")
            Message.print(sender, "/combatadmin hitbox: Toggle hitbox visualization")
            Message.print(sender, "/combatadmin hat: Manage player hat collections")
        }

        addSubcommand(CombatAdminGiveCommand())
        addSubcommand(CombatAdminExplosionCommand())
        addSubcommand(CombatAdminHitboxCommand())
        addSubcommand(CombatAdminHatCommand())
    }
}

class CombatAdminGiveCommand : Command("give", "combat.admin") {
    init {
        setDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /combatadmin give <item-name>")
        }

        val itemArg = ArgumentItem.create("item-name")

        addSyntax({ player: Player, context ->
            player.inventory.addItemStack(context[itemArg].toItemStack())
        }, itemArg)
    }
}

class CombatAdminExplosionCommand : Command("explosion", "combat.admin") {
    init {
        setDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /combatadmin explosion <radius> <fire>")
        }

        val radiusArg = ArgumentType.Integer("radius")
        val fireArg = ArgumentType.Double("fire")

        addSyntax({ player: Player, context ->
            Explosion(player.instance, player.position, context[radiusArg], context[fireArg])
        }, radiusArg, fireArg)
    }
}

class CombatAdminHitboxCommand : Command("hitbox", "combat.admin") {
    init {
        setDefaultExecutor { player, _ ->
            if (Hitbox.viewingHitboxes.contains(player)) {
                Hitbox.viewingHitboxes.remove(player)
                Message.print(player, "Hitbox visualization disabled")
            } else {
                Hitbox.viewingHitboxes.add(player)
                Message.print(player, "Hitbox visualization enabled")
            }
        }
    }
}

class CombatAdminHatCommand : Command("hat", "combat.admin") {
    init {
        setDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage:")
            Message.print(sender, "/combatadmin hat give <player-name> <hat-name>")
            Message.print(sender, "/combatadmin hat remove <player-name> <hat-name>")
        }

        addSubcommand(CombatAdminHatGiveCommand())
        addSubcommand(CombatAdminHatRemoveCommand())
    }
}

class CombatAdminHatGiveCommand : Command("give", "combat.admin") {
    init {
        val playerArg = ArgumentEntity("player-name").onlyPlayers(true).singleEntity(true)
        val hatArg = ArgumentHat.create("hat-name")

        setDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /combatadmin hat give <player-name> <hat-name>")
        }

        addSyntax({ sender: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.print(sender, "Player not found")
                    return@addSyntax
                }
            val hat = context[hatArg]

            HatCollection.give(target.uuid, hat)
            Message.print(sender, "Gave hat '${hat.name}' to ${target.username}")
        }, playerArg, hatArg)
    }
}

class CombatAdminHatRemoveCommand : Command("remove", "combat.admin") {
    init {
        val playerArg = ArgumentEntity("player-name").onlyPlayers(true).singleEntity(true)
        val hatArg = ArgumentHat.create("hat-name")

        setDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /combatadmin hat remove <player-name> <hat-name>")
        }

        addSyntax({ sender: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.print(sender, "Player not found")
                    return@addSyntax
                }
            val hat = context[hatArg]

            HatCollection.remove(target.uuid, hat)
            Message.print(sender, "Removed hat '${hat.name}' from ${target.username}")
        }, playerArg, hatArg)
    }
}
