package net.aechronis.hats

import net.aechronis.combat.listeners.HatListener
import net.aechronis.combat.objects.Hat
import net.aechronis.hats.constants.Hats
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.minestom.server.MinecraftServer

class HatsModule : AechronisModule {
    override val id = "hats"
    override val dependencies = setOf("combat")

    override fun configure(context: ModuleContext) {
        Hat.registerHats(Hats.gasMask)
    }

    override fun initialize(context: ModuleContext) {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            HatListener.refresh(player)
            player.inventory.update()
        }
    }
}
