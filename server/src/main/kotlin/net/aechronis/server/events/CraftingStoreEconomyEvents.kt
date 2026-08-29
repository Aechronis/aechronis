package net.aechronis.server.events

import net.minestom.server.event.Event
import java.util.UUID

class CraftingStoreWithdrawRequest(
    val playerId: UUID,
    val amount: Long,
    val reason: String,
    var reference: UUID? = null,
) : Event

class CraftingStoreRefundRequest(
    val playerId: UUID,
    val amount: Long,
    val reason: String,
    var refunded: Boolean = false,
) : Event

class CraftingStoreBalanceRequest(
    val playerId: UUID,
    var balance: Long? = null,
) : Event
