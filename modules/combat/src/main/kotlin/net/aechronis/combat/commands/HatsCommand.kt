package net.aechronis.combat.commands

import net.aechronis.combat.objects.HatMenu
import net.aechronis.utils.Command

class HatsCommand : Command("hats", null, "hat", "h") {
    init {
        setDefaultExecutor { player, _ -> HatMenu.open(player) }
    }
}
