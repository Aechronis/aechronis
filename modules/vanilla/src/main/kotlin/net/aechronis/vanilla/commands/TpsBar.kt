package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.aechronis.vanilla.managers.TpsBar as TpsBarManager

class TpsBar : Command("tpsbar", "vanilla.tpsbar") {
    private val playerArg = PlayerTargets.argument()

    init {
        setDefaultExecutor { sender: Player, _ ->
            val enabled = TpsBarManager.toggle(sender)
            sender.sendMessage(
                Component.text(
                    "TPS bar ${if (enabled) "enabled" else "disabled"}.",
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
        }

        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerArg]) ?: return@addSyntax
            targets.forEach { target ->
                val enabled = TpsBarManager.toggle(target)
                if (target !== sender) {
                    target.sendMessage(
                        Component.text(
                            "Your TPS bar ${if (enabled) "was enabled" else "was disabled"}.",
                            NamedTextColor.LIGHT_PURPLE,
                        ),
                    )
                }
            }
            sender.sendMessage(Component.text("Toggled TPS bar for ${targets.size} player(s).", NamedTextColor.LIGHT_PURPLE))
        }, playerArg)
    }
}
