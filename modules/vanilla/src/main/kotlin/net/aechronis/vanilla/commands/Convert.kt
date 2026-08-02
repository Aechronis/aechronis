package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Blocks

class Convert : Command("convert", "vanilla.convert") {
    init {
        setDefaultExecutor { player, _ ->
            Blocks.openConverter(player)
        }
    }
}
