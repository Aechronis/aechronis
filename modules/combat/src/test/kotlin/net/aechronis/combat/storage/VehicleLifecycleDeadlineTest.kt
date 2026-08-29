package net.aechronis.combat.storage

import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VehicleLifecycleDeadlineTest {
    @Test
    fun `chunk load exposes its original failure`() {
        val expected = IllegalArgumentException("expected")
        val error =
            assertFailsWith<IllegalArgumentException> {
                VehicleLifecycleDeadline
                    .after(Duration.ofSeconds(1))
                    .await(CompletableFuture.failedFuture(expected), "test chunk")
            }

        assertSame(expected, error)
    }

    @Test
    fun `chunk load wait is bounded by the lifecycle deadline`() {
        val error =
            assertFailsWith<IllegalStateException> {
                VehicleLifecycleDeadline
                    .after(Duration.ofMillis(20))
                    .await(CompletableFuture<Void>(), "test chunk")
            }

        assertTrue(error.message.orEmpty().contains("vehicle lifecycle deadline"))
    }

    @Test
    fun `chunk load wait preserves interruption`() {
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<IllegalStateException> {
                VehicleLifecycleDeadline
                    .after(Duration.ofSeconds(1))
                    .await(CompletableFuture<Void>(), "test chunk")
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
