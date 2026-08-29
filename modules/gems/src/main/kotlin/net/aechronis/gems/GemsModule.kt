package net.aechronis.gems

import net.aechronis.server.VoteRewardRequest
import net.aechronis.server.VoteRewardsAvailableEvent
import net.aechronis.server.events.CraftingStoreBalanceRequest
import net.aechronis.server.events.CraftingStoreRefundRequest
import net.aechronis.server.events.CraftingStoreWithdrawRequest
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.vanilla.managers.Crates
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent

class GemsModule : AechronisModule {
    override val id = "gems"
    override val dependencies = setOf("nodes", "utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        Gems.initialize()
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
        context.addListener(CraftingStoreWithdrawRequest::class.java) { event ->
            event.reference = Gems.craftingStoreWithdraw(event.playerId, event.amount, event.reason)
        }
        context.addListener(CraftingStoreRefundRequest::class.java) { event ->
            event.refunded = Gems.craftingStoreRefund(event.playerId, event.amount, event.reason)
        }
        context.addListener(CraftingStoreBalanceRequest::class.java) { event ->
            event.balance = Gems.craftingStoreBalance(event.playerId)
        }
        MinecraftServer.getGlobalEventHandler().call(VoteRewardsAvailableEvent())
    }

    override fun shutdown(context: ModuleContext) = Gems.shutdown()
}
