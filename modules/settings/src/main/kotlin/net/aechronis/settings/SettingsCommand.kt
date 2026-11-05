package net.aechronis.settings

import net.aechronis.utils.Command

class SettingsCommand : Command("settings") {
    init {
        setDefaultExecutor { player, _ -> SettingsMenu.open(player) }
    }
}
