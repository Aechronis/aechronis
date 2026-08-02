package net.aechronis.logger.commands

import net.aechronis.logger.Logger
import net.aechronis.logger.utils.RollbackExecution
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Recover : Command("recover", "logger.rollback.recover") {
    init {
        setDefaultExecutor { sender, _ ->
            sender.sendMessage(
                Component.text(
                    "Usage: /logger recover acknowledge (only after manually verifying the interrupted operation)",
                    NamedTextColor.GOLD,
                ),
            )
        }
        val acknowledge = ArgumentType.Literal("acknowledge")
        addSyntax({ sender: Player, _ ->
            Logger.rollbackService.acknowledgeRecoveryAsync().whenComplete { count, failure ->
                if (failure != null) {
                    RollbackExecution.reportFailure(sender, "recovery acknowledgement failed", failure)
                } else {
                    sender.sendMessage(
                        Component.text(
                            if (count > 0) {
                                "[Logger] acknowledged $count interrupted operation(s)"
                            } else {
                                "[Logger] no recovery-required operations"
                            },
                            if (count > 0) NamedTextColor.GOLD else NamedTextColor.GRAY,
                        ),
                    )
                }
            }
        }, acknowledge)
    }
}
