package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.utils.Message
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Fly : Command("fly", "vanilla.fly") {
    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/fly - Toggle fly mode")
            Message.print(player, "/fly <speed> - Set fly speed")
        }

        val speed = ArgumentType.Float("speed")

        addSyntax({ sender: Player, _ ->
            sender.isAllowFlying = !sender.isAllowFlying
            sender.isFlying = sender.isAllowFlying
            Message.print(sender, "Fly mode ${if (sender.isAllowFlying) "enabled" else "disabled"}.")
        })

        addSyntax({ sender: Player, context ->
            sender.flyingSpeed = context[speed] / 20
            Message.print(sender, "Fly speed set to ${context[speed]}")
        }, speed)
    }
}
