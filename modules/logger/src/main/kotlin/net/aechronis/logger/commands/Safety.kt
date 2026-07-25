package net.aechronis.logger.commands

import net.aechronis.logger.objects.RollbackSafety
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Safety : Command("safety", "logger.safety") {
    init {
        setDefaultExecutor { sender: Player, _ -> show(sender, RollbackSafety.toggle(sender.uuid)) }
        val mode = ArgumentType.Word("mode").from("on", "off", "toggle")
        addSyntax({ sender: Player, context ->
            val enabled =
                when (context.get(mode)) {
                    "on" -> true
                    "off" -> false
                    else -> RollbackSafety.toggle(sender.uuid)
                }
            if (context.get(mode) != "toggle") RollbackSafety.set(sender.uuid, enabled)
            show(sender, enabled)
        }, mode)
    }

    private fun show(
        sender: Player,
        enabled: Boolean,
    ) {
        sender.sendMessage(
            Component.text(
                "[Logger] rollback confirmations ${if (enabled) "enabled" else "disabled until logout"}",
                if (enabled) NamedTextColor.GREEN else NamedTextColor.YELLOW,
            ),
        )
    }
}
