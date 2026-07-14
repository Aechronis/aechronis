package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Blocks
import net.aechronis.vanilla.utils.VanillaCommand

class Convert : VanillaCommand("convert", "vanilla.convert") {
    init {
        setDefaultExecutor { player, _ ->
            Blocks.openConverter(player)
        }
    }
}
