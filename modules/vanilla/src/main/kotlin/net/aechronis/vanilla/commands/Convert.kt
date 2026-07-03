package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Blocks
import net.aechronis.vanilla.utils.Command

class Convert : Command("convert", "vanilla.convert") {
    init {
        setDefaultExecutor { player, _ ->
            Blocks.openConverter(player)
        }
    }
}
