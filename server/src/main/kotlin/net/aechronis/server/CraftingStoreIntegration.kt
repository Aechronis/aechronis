package net.aechronis.server

import net.aechronis.craftingstore.CraftingStoreEconomy
import net.aechronis.craftingstore.CraftingStoreModule
import net.aechronis.craftingstore.CraftingStoreOptions
import net.aechronis.craftingstore.Withdrawal
import net.aechronis.gems.Gems
import net.aechronis.utils.hasPermission
import net.minestom.server.entity.Player
import java.nio.file.Path
import java.util.UUID

object CraftingStoreIntegration {
    private val economy =
        object : CraftingStoreEconomy {
            override fun tryWithdraw(
                playerId: UUID,
                amount: Long,
                reason: String,
            ): Withdrawal? {
                if (amount <= 0) return null
                return Gems.craftingStoreWithdraw(playerId, amount, reason)?.let { Withdrawal(it, amount) }
            }

            override fun refund(
                playerId: UUID,
                amount: Long,
                reason: String,
            ): Boolean = amount > 0 && Gems.craftingStoreRefund(playerId, amount, reason)

            override fun balance(playerId: UUID): Long? = Gems.craftingStoreBalance(playerId)
        }

    fun initialize() {
        CraftingStoreModule.initialize(
            CraftingStoreOptions(
                dataDirectory = Path.of("craftingstore"),
                permissionChecker = { player: Player, permission: String -> player.hasPermission(permission) },
                economy = economy,
            ),
        )
    }

    fun shutdown() = CraftingStoreModule.shutdown()
}
