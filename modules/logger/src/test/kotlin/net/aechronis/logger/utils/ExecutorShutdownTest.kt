package net.aechronis.logger.utils

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExecutorShutdownTest {
    @Test
    fun `lifecycle future exposes original failure`() {
        val expected = IllegalArgumentException("expected")
        val error =
            assertFailsWith<IllegalArgumentException> {
                awaitLifecycleFuture(
                    CompletableFuture.failedFuture(expected),
                    "test future",
                    Duration.ofSeconds(1),
                )
            }

        assertSame(expected, error)
    }

    @Test
    fun `shutdown interrupts work and fails within its deadline when work ignores interruption`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Simulate blocking external work which does not honor interruption.
                }
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        try {
            assertFailsWith<IllegalStateException> {
                shutdownExecutor(
                    executor,
                    "test executor",
                    gracefulTimeout = Duration.ofMillis(20),
                    forcedTimeout = Duration.ofMillis(20),
                )
            }
        } finally {
            release.countDown()
            shutdownExecutor(
                executor,
                "test executor retry",
                gracefulTimeout = Duration.ofSeconds(1),
                forcedTimeout = Duration.ofSeconds(1),
            )
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }
}
