package net.aechronis.craftingstore

import java.util.UUID

/** Thread-safe economy boundary used by the optional in-game purchase flow. */
interface CraftingStoreEconomy {
    fun tryWithdraw(
        playerId: UUID,
        amount: Long,
        reason: String,
    ): Withdrawal?

    fun refund(
        playerId: UUID,
        amount: Long,
        reason: String,
    ): Boolean

    fun balance(playerId: UUID): Long? = null
}

data class Withdrawal(
    val reference: UUID,
    val amount: Long,
)
