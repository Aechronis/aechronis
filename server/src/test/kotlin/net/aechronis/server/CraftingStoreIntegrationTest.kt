package net.aechronis.server

import net.aechronis.craftingstore.Withdrawal
import net.aechronis.server.events.CraftingStoreBalanceRequest
import net.aechronis.server.events.CraftingStoreRefundRequest
import net.aechronis.server.events.CraftingStoreWithdrawRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CraftingStoreIntegrationTest {
    @Test
    fun `economy bridge delegates requests and returns their results`() {
        val playerId = UUID.randomUUID()
        val reference = UUID.randomUUID()
        val economy =
            CraftingStoreEconomyBridge { request ->
                when (request) {
                    is CraftingStoreWithdrawRequest -> {
                        assertEquals(playerId, request.playerId)
                        assertEquals(25L, request.amount)
                        assertEquals("purchase", request.reason)
                        request.reference = reference
                    }

                    is CraftingStoreRefundRequest -> {
                        assertEquals(playerId, request.playerId)
                        assertEquals(25L, request.amount)
                        assertEquals(reference.toString(), request.reason)
                        request.refunded = true
                    }

                    is CraftingStoreBalanceRequest -> {
                        assertEquals(playerId, request.playerId)
                        request.balance = 75L
                    }

                    else -> error("Unexpected request: ${request.javaClass.name}")
                }
            }

        assertEquals(Withdrawal(reference, 25L), economy.tryWithdraw(playerId, 25L, "purchase"))
        assertTrue(economy.refund(playerId, 25L, reference.toString()))
        assertEquals(75L, economy.balance(playerId))
    }

    @Test
    fun `economy bridge fails closed without a provider`() {
        val economy = CraftingStoreEconomyBridge { }
        val playerId = UUID.randomUUID()

        assertNull(economy.tryWithdraw(playerId, 25L, "purchase"))
        assertFalse(economy.refund(playerId, 25L, "refund"))
        assertNull(economy.balance(playerId))
    }

    @Test
    fun `economy bridge rejects non-positive transactions without dispatching`() {
        var dispatches = 0
        val economy = CraftingStoreEconomyBridge { dispatches += 1 }
        val playerId = UUID.randomUUID()

        assertNull(economy.tryWithdraw(playerId, 0L, "purchase"))
        assertNull(economy.tryWithdraw(playerId, -1L, "purchase"))
        assertFalse(economy.refund(playerId, 0L, "refund"))
        assertFalse(economy.refund(playerId, -1L, "refund"))
        assertEquals(0, dispatches)
    }
}
