package net.aechronis.logger.commands

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.RollbackActor
import net.aechronis.logger.utils.RollbackExecution
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player

class Redo : Command("redo", "logger.redo") {
    init {
        setDefaultExecutor { sender: Player, _ ->
            sender.sendMessage(Component.text("[Logger] redo started...", NamedTextColor.GRAY))
            Logger.rollbackService.redoAsync(RollbackActor(sender.uuid, sender.username)).whenComplete { result, failure ->
                if (failure == null) {
                    RollbackExecution.reportSuccess(sender, result, "Redo")
                } else {
                    RollbackExecution.reportFailure(sender, "redo failed", failure)
                }
            }
        }
    }
}
