package net.aechronis.server

import net.aechronis.craftingstore.CraftingStoreEconomy
import net.aechronis.craftingstore.CraftingStoreModule
import net.aechronis.craftingstore.CraftingStoreOptions
import net.aechronis.craftingstore.Withdrawal
import net.aechronis.server.events.CraftingStoreBalanceRequest
import net.aechronis.server.events.CraftingStoreRefundRequest
import net.aechronis.server.events.CraftingStoreWithdrawRequest
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import java.nio.file.Path
import java.util.UUID

object CraftingStoreIntegration {
    private val economy = CraftingStoreEconomyBridge { request -> Server.eventNode.call(request) }

    fun initialize() {
        CraftingStoreModule.initialize(
            CraftingStoreOptions(
                dataDirectory = Path.of("craftingstore"),
                permissionChecker = { player: Player, permission: String -> player.hasPermission(permission) },
                economy = economy,
                registerCommand = {
                    ModuleCommands
                        .register(it)
                },
                unregisterCommand = ModuleCommands::unregister,
            ),
        ) { node -> ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), node) }
    }

    fun shutdown() = CraftingStoreModule.shutdown()
}

internal class CraftingStoreEconomyBridge(
    private val dispatch: (Event) -> Unit,
) : CraftingStoreEconomy {
    override fun tryWithdraw(
        playerId: UUID,
        amount: Long,
        reason: String,
    ): Withdrawal? {
        if (amount <= 0) return null
        val request = CraftingStoreWithdrawRequest(playerId, amount, reason)
        dispatch(request)
        return request.reference?.let { reference -> Withdrawal(reference, amount) }
    }

    override fun refund(
        playerId: UUID,
        amount: Long,
        reason: String,
    ): Boolean {
        if (amount <= 0) return false
        val request = CraftingStoreRefundRequest(playerId, amount, reason)
        dispatch(request)
        return request.refunded
    }

    override fun balance(playerId: UUID): Long? {
        val request = CraftingStoreBalanceRequest(playerId)
        dispatch(request)
        return request.balance
    }
}
