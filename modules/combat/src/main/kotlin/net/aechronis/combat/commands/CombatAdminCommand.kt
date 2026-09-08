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
import net.aechronis.combat.listeners.HatListener
import net.aechronis.combat.objects.Explosion
import net.aechronis.combat.objects.HatMenu
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
            val item = context[itemArg]
            val stack = item.toItemStack()
            if (!player.inventory.addItemStack(stack)) player.dropItem(stack)
        }, itemArg)
    }
}

class CombatAdminExplosionCommand : Command("explosion", "combat.admin") {
    init {
        setDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /combatadmin explosion <radius> <fire> <damage>")
        }

        val radiusArg = ArgumentType.Integer("radius")
        val fireArg = ArgumentType.Double("fire")
        val damageArg = ArgumentType.Float("damage")

        addSyntax({ player: Player, context ->
            Explosion(
                instance = player.instance,
                pos = player.position,
                radius = context[radiusArg],
                fire = context[fireArg],
                damage = context[damageArg],
            )
        }, radiusArg, fireArg, damageArg)
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
            Message.print(sender, "/combatadmin hat remove <player-name> <hat-number>")
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

            val instance = HatCollection.give(target.uuid, hat)
            HatMenu.refresh(target)
            Message.print(sender, "Gave ${hat.name} #${instance.number} to ${target.username}")
        }, playerArg, hatArg)
    }
}

class CombatAdminHatRemoveCommand : Command("remove", "combat.admin") {
    init {
        val playerArg = ArgumentEntity("player-name").onlyPlayers(true).singleEntity(true)
        val numberArg = ArgumentType.Long("hat-number").min(1L)

        setDefaultExecutor { sender, _ ->
            Message.print(sender, "Usage: /combatadmin hat remove <player-name> <hat-number>")
        }

        addSyntax({ sender: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.print(sender, "Player not found")
                    return@addSyntax
                }
            val number = context[numberArg]
            val hat =
                HatCollection.remove(target.uuid, number) ?: run {
                    Message.error(sender, "${target.username} does not own hat #$number")
                    return@addSyntax
                }
            HatListener.refresh(target)
            HatMenu.refresh(target)
            Message.print(sender, "Removed ${hat.hatName} #${hat.number} from ${target.username}")
        }, playerArg, numberArg)
    }
}
