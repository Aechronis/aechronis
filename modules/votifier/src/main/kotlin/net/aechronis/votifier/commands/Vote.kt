package net.aechronis.votifier.commands

import net.aechronis.utils.Command
import net.aechronis.votifier.managers.VoteLinks

class Vote : Command("vote") {
    init {
        setDefaultExecutor { player, _ -> VoteLinks.show(player) }
    }
}
