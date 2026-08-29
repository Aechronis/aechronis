package net.aechronis.server.modules

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModuleCheckpointTrackerTest {
    @Test
    fun `caller timeout neither hides nor duplicates underlying work`() {
        val tracker = ModuleCheckpointTracker(Duration.ofMillis(30), Duration.ofMillis(250))
        val underlying = CompletableFuture<Void>()
        var starts = 0

        val first =
            tracker.request {
                starts += 1
                underlying
            }
        assertFailsWith<ExecutionException> { first.get(1, TimeUnit.SECONDS) }
        assertTrue(tracker.hasPending())

        val second =
            tracker.request {
                starts += 1
                CompletableFuture.completedFuture(null)
            }
        assertEquals(1, starts)
        underlying.complete(null)
        second.get(1, TimeUnit.SECONDS)
        tracker.awaitCompletion()
        assertFalse(tracker.hasPending())
    }

    @Test
    fun `completed exceptional checkpoint remains a lifecycle failure`() {
        val tracker = ModuleCheckpointTracker(Duration.ofSeconds(1), Duration.ofSeconds(1))
        tracker.request { CompletableFuture.failedFuture(IllegalStateException("save failed")) }

        val error = assertFailsWith<IllegalStateException> { tracker.awaitCompletion() }
        assertEquals("save failed", error.message)
        assertFalse(tracker.hasPending())
    }

    @Test
    fun `successful retry clears a retained checkpoint failure`() {
        val tracker = ModuleCheckpointTracker(Duration.ofSeconds(1), Duration.ofSeconds(1))
        var starts = 0
        val first =
            tracker.request {
                starts += 1
                CompletableFuture.failedFuture(IllegalStateException("first save failed"))
            }
        assertFailsWith<ExecutionException> { first.get(1, TimeUnit.SECONDS) }

        val retry =
            tracker.request {
                starts += 1
                CompletableFuture.completedFuture(null)
            }
        retry.get(1, TimeUnit.SECONDS)

        tracker.awaitCompletion()
        assertEquals(2, starts)
        assertFalse(tracker.hasPending())
    }

    @Test
    fun `failed retry replaces the retained lifecycle failure`() {
        val tracker = ModuleCheckpointTracker(Duration.ofSeconds(1), Duration.ofSeconds(1))
        tracker.request { CompletableFuture.failedFuture(IllegalStateException("first save failed")) }
        tracker.request { CompletableFuture.failedFuture(IllegalArgumentException("retry failed")) }

        val error = assertFailsWith<IllegalArgumentException> { tracker.awaitCompletion() }
        assertEquals("retry failed", error.message)
    }

    @Test
    fun `synchronous start failure remains visible and can be retried`() {
        val tracker = ModuleCheckpointTracker(Duration.ofSeconds(1), Duration.ofSeconds(1))
        val expected = IllegalStateException("synchronous save failure")
        val first = tracker.request { throw expected }
        assertFailsWith<ExecutionException> { first.get(1, TimeUnit.SECONDS) }

        val lifecycleFailure = assertFailsWith<IllegalStateException> { tracker.awaitCompletion() }
        assertSame(expected, lifecycleFailure)

        tracker.request { CompletableFuture.completedFuture(null) }.get(1, TimeUnit.SECONDS)
        tracker.awaitCompletion()
    }

    @Test
    fun `authoritative save settles a completed checkpoint failure after quiescence`() {
        val tracker = ModuleCheckpointTracker(Duration.ofSeconds(1), Duration.ofSeconds(1))
        tracker.request { CompletableFuture.failedFuture(IllegalStateException("periodic save failed")) }

        tracker.awaitQuiescence()
        tracker.settleAfterAuthoritativeSave()

        tracker.awaitCompletion()
        assertFalse(tracker.hasPending())
    }
}
