package net.aechronis.combat.commands

import net.aechronis.combat.commands.arguments.ArgumentHat
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.storage.HatCollection
import net.aechronis.combat.utils.Message
import net.aechronis.utils.Command
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import kotlin.math.roundToInt

class HatsCommand : Command("hats", null, "hat", "h") {
    init {
        setDefaultExecutor { player, _ ->
            listHats(player)
            equipHeldHat(player)
        }

        addSubcommand(HatsEquipCommand())
    }
}

private class HatsEquipCommand : Command("equip") {
    init {
        val hatArg = ArgumentHat.create("hat-name")

        setDefaultExecutor { player, _ ->
            Message.print(player, "Usage: /hats equip <hat-name>")
        }

        addSyntax({ player: Player, context ->
            val hat = context[hatArg]
            if (!HatCollection.owns(player.uuid, hat)) {
                Message.error(player, "You have not unlocked ${hat.name}")
                return@addSyntax
            }
            equipHat(player, hat.toItemStack())
            Message.print(player, "Equipped ${hat.name}")
        }, hatArg)
    }
}

private fun listHats(player: Player) {
    val hats = HatCollection.hats(player.uuid)
    if (hats.isEmpty()) {
        Message.print(player, "You have not unlocked any hat skins")
        return
    }

    Message.print(player, "Available hat skins:")
    for (hat in hats) {
        val protection = (hat.protection * 100).roundToInt()
        Message.print(player, "- ${hat.name} ($protection% protection)")
    }
    Message.print(player, "Use /hats equip <hat-name>, or hold a hat and run /hats to equip it")
}

private fun equipHeldHat(player: Player) {
    val heldHat = Item.getFromItemStack(player.itemInMainHand) as? Hat ?: return
    if (!HatCollection.owns(player.uuid, heldHat)) {
        Message.error(player, "You have not unlocked ${heldHat.name}")
        return
    }

    val previousHelmet = player.helmet
    player.helmet = player.itemInMainHand
    player.itemInMainHand = previousHelmet
    Message.print(player, "Equipped ${heldHat.name}")
}

private fun equipHat(
    player: Player,
    hat: ItemStack,
) {
    val previousHelmet = player.helmet
    if (!previousHelmet.isAir && !player.inventory.addItemStack(previousHelmet)) {
        player.dropItem(previousHelmet)
    }
    player.helmet = hat
}
