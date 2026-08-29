package net.aechronis.nodes.objects

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoundedChunkLoadBatchTest {
    @Test
    fun `distinct chunk loads share one total deadline`() {
        var now = 0L
        val loads = BoundedChunkLoadBatch<String>(Duration.ofNanos(10)) { now }

        loads.await("first", "loading first chunk") { CompletableFuture.completedFuture(null) }
        now = 11L

        val failure =
            assertFailsWith<IllegalStateException> {
                loads.await("second", "loading second chunk") { CompletableFuture.completedFuture(null) }
            }
        assertIs<TimeoutException>(failure.cause)
    }

    @Test
    fun `a failed chunk load is deduplicated and exposes its original cause`() {
        val expected = IllegalArgumentException("broken chunk")
        val loads = BoundedChunkLoadBatch<String>(Duration.ofSeconds(1))
        var starts = 0

        repeat(2) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    loads.await("same", "loading chunk") {
                        starts++
                        CompletableFuture.failedFuture<Void>(expected)
                    }
                }
            assertSame(expected, failure)
        }
        assertEquals(1, starts)
    }

    @Test
    fun `interrupting a chunk wait preserves the interrupt status`() {
        val loads = BoundedChunkLoadBatch<String>(Duration.ofSeconds(5))
        val waiting = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val thread =
            Thread {
                try {
                    loads.await("stalled", "loading chunk") {
                        waiting.countDown()
                        CompletableFuture<Void>()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }

        thread.start()
        assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(1_000)

        assertTrue(!thread.isAlive)
        assertTrue(interrupted.get())
        assertIs<InterruptedException>(failure.get()?.cause)
    }
}
