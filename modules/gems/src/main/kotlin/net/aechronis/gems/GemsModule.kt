package net.aechronis.gems

import net.aechronis.server.VoteRewardRequest
import net.aechronis.server.VoteRewardsAvailableEvent
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.aechronis.vanilla.managers.Crates
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent

class GemsModule : AechronisModule {
    override val id = "gems"
    override val dependencies = setOf("nodes", "utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        Gems.initialize()
        measure("Vote rewards") {
            context.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
                Gems.rememberPlayer(event.player)
            }
            context.addListener(VoteRewardRequest::class.java) { event ->
                val crate = runCatching { Crates.itemFor(event.itemId) }.getOrNull() ?: return@addListener
                event.granted =
                    Gems.grantVoteReward(event.player, event.gems) {
                        event.player.inventory.addItemStack(crate) || event.player.dropItem(crate)
                    }
            }
            MinecraftServer.getGlobalEventHandler().call(VoteRewardsAvailableEvent())
        }
    }

    override fun shutdown(context: ModuleContext) = Gems.shutdown()
}
