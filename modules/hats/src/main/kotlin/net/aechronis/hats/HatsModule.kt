package net.aechronis.hats

import net.aechronis.combat.objects.Hat
import net.aechronis.hats.constants.Hats
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class HatsModule : AechronisModule {
    override val id = "hats"
    override val dependencies = setOf("combat")
    override val reloadTogether = setOf("combat")

    override fun configure(context: ModuleContext) {
        Hat.registerHats(Hats.gasMask)
    }
}
