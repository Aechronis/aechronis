package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FactoriesTest : ManagerTest() {
    @Test
    fun `factory chunk batch has one bounded wait`() {
        val unfinished = listOf(CompletableFuture<Void>(), CompletableFuture<Void>())

        assertFailsWith<IllegalStateException> {
            Factories.awaitChunkLoads(unfinished, 20, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `factory chunk batch exposes the load failure`() {
        val cause = IllegalArgumentException("factory chunk failed")
        val failed = CompletableFuture.failedFuture<Void>(cause)

        val thrown =
            assertFailsWith<IllegalArgumentException> {
                Factories.awaitChunkLoads(listOf(failed), 1, TimeUnit.SECONDS)
            }
        assertSame(cause, thrown)
    }
}
