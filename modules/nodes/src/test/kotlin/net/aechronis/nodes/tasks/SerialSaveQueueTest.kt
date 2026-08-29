package net.aechronis.nodes.tasks

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerialSaveQueueTest {
    @Test
    fun `await idle follows work appended behind the observed tail`() {
        Executors.newFixedThreadPool(2).use { executor ->
            val queue = SerialSaveQueue(executor)
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val releaseSecond = CountDownLatch(1)
            queue.submit {
                firstStarted.countDown()
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            val waiter = CompletableFuture.runAsync { queue.awaitIdle(5, TimeUnit.SECONDS) }
            queue.submit { assertTrue(releaseSecond.await(5, TimeUnit.SECONDS)) }
            releaseFirst.countDown()
            assertFalse(waiter.isDone)
            releaseSecond.countDown()
            waiter.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `await idle times out instead of waiting forever`() {
        Executors.newSingleThreadExecutor().use { executor ->
            val queue = SerialSaveQueue(executor)
            val release = CountDownLatch(1)
            queue.submit { release.await() }
            try {
                assertFailsWith<TimeoutException> { queue.awaitIdle(10, TimeUnit.MILLISECONDS) }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `later save cannot start before earlier save completes`() {
        Executors.newFixedThreadPool(2).use { executor ->
            val queue = SerialSaveQueue(executor)
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val calls = mutableListOf<String>()

            val first =
                queue.submit {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    calls += "first"
                }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            val second =
                queue.submit {
                    calls += "second"
                    secondStarted.countDown()
                }

            assertEquals(second, queue.current())
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            CompletableFuture.allOf(first, second).join()

            assertEquals(listOf("first", "second"), calls)
        }
    }

    @Test
    fun `quiescence permits an authoritative save to replace an earlier failure`() {
        val queue = SerialSaveQueue(Runnable::run)
        queue.submit { error("periodic save failed") }

        queue.awaitQuiescence(1, TimeUnit.SECONDS)
        val replacement = queue.submit {}

        replacement.get(1, TimeUnit.SECONDS)
        queue.awaitIdle(1, TimeUnit.SECONDS)
    }
}
