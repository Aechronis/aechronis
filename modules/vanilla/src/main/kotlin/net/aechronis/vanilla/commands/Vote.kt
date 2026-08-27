package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.VoteLinks

class Vote : Command("vote") {
    init {
        setDefaultExecutor { player, _ -> VoteLinks.show(player) }
    }
}
