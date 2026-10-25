package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.NodesCommand

/** Shows the sender's stone coin balance: /balance. */
class BalanceCommand : NodesCommand("balance", null, "coins") {
    init {
        setDefaultExecutor { player, resident, _ ->
            Message.print(player, "Stone coins: ${resident.blockCoins}")
        }
    }
}
