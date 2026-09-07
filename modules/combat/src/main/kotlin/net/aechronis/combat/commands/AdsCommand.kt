package net.aechronis.combat.commands

import net.aechronis.combat.Combat
import net.aechronis.combat.utils.Message
import net.aechronis.utils.Command

class AdsCommand : Command("ads") {
    init {
        setDefaultExecutor { player, _ ->
            val disabled = Combat.toggleAdsAnimation(player.uuid)
            Message.print(player, "First-person ADS animation ${if (disabled) "disabled" else "enabled"}")
        }
    }
}
