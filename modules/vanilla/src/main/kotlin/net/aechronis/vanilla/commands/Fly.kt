package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Fly : Command("fly", "vanilla.fly") {
    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/fly - Toggle fly mode", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/fly <speed> - Set fly speed", NamedTextColor.LIGHT_PURPLE))
        }

        val speed = ArgumentType.Float("speed")

        addSyntax({ sender: Player, _ ->
            sender.isAllowFlying = !sender.isAllowFlying
            sender.isFlying = sender.isAllowFlying
            sender.sendMessage(
                Component.text(
                    "Fly mode ${if (sender.isAllowFlying) "enabled" else "disabled"}.",
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
        })

        addSyntax({ sender: Player, context ->
            sender.flyingSpeed = context[speed] / 20
            sender.sendMessage(Component.text("Fly speed set to ${context[speed]}", NamedTextColor.LIGHT_PURPLE))
        }, speed)
    }
}
