package net.aechronis.server.modules

import net.minestom.server.event.Event
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.timer.TaskSchedule
import java.net.URLClassLoader
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModuleResourceScopeTest {
    @Test
    fun `quiescing waits for active listeners and rejects later events`() {
        val scope = ModuleResourceScope(javaClass.classLoader)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)

        scope.addListener(TestEvent::class.java) {
            calls.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }

        try {
            val dispatch = executor.submit { scope.eventNode.call(TestEvent()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val quiesce = executor.submit(scope::quiesceEvents)
            assertFailsWith<TimeoutException> { quiesce.get(100, TimeUnit.MILLISECONDS) }

            release.countDown()
            dispatch.get(5, TimeUnit.SECONDS)
            quiesce.get(5, TimeUnit.SECONDS)

            scope.eventNode.call(TestEvent())
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `quiescing has a bounded wait`() {
        val scope = ModuleResourceScope(javaClass.classLoader)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        scope.addListener(TestEvent::class.java) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        try {
            val dispatch = executor.submit { scope.eventNode.call(TestEvent()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { scope.quiesceEvents(Duration.ofMillis(50)) }
            release.countDown()
            dispatch.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `quiescing also waits for active scheduler callbacks`() {
        val scope = ModuleResourceScope(javaClass.classLoader)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val callback =
                executor.submit {
                    scope.invoke(
                        Supplier {
                            calls.incrementAndGet()
                            entered.countDown()
                            assertTrue(release.await(5, TimeUnit.SECONDS))
                            TaskSchedule.stop()
                        },
                    )
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { scope.quiesceEvents(Duration.ofMillis(50)) }

            release.countDown()
            callback.get(5, TimeUnit.SECONDS)
            scope.quiesceEvents(Duration.ofSeconds(1))
            scope.invoke(
                Supplier {
                    calls.incrementAndGet()
                    TaskSchedule.stop()
                },
            )
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `quiescing waits for active async work and rejects later submissions`() {
        val scope = ModuleResourceScope(javaClass.classLoader)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()

        val callback =
            scope.runAsync {
                calls.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { scope.quiesceEvents(Duration.ofMillis(50)) }

            release.countDown()
            callback.get(5, TimeUnit.SECONDS)
            scope.quiesceEvents(Duration.ofSeconds(1))

            val rejected = scope.runAsync { calls.incrementAndGet() }
            assertTrue(rejected.isCompletedExceptionally)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `cancelling an async result cannot abandon queued lifecycle work`() {
        val executor = Executors.newSingleThreadExecutor()
        val queueOccupied = CountDownLatch(1)
        val releaseQueue = CountDownLatch(1)
        val callbackRan = CountDownLatch(1)
        val scope = ModuleResourceScope(javaClass.classLoader, executor)
        val blocker =
            executor.submit {
                queueOccupied.countDown()
                assertTrue(releaseQueue.await(5, TimeUnit.SECONDS))
            }

        try {
            assertTrue(queueOccupied.await(5, TimeUnit.SECONDS))
            val result = scope.runAsync { callbackRan.countDown() }
            assertTrue(result.cancel(true))
            assertFailsWith<TimeoutException> { scope.quiesceEvents(Duration.ofMillis(50)) }

            releaseQueue.countDown()
            blocker.get(5, TimeUnit.SECONDS)
            assertTrue(callbackRan.await(5, TimeUnit.SECONDS))
            scope.quiesceEvents(Duration.ofSeconds(1))
        } finally {
            releaseQueue.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `quiescing waits for direct event node callbacks and rejects cached callbacks`() {
        val moduleClassLoader = URLClassLoader(emptyArray(), javaClass.classLoader)
        val scope = ModuleResourceScope(moduleClassLoader)
        val root = EventNode.all("direct-root")
        val moduleNode = EventNode.all("direct-module")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        moduleNode.addListener(TestEvent::class.java) {
            calls.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        root.addChild(moduleNode)
        // Force both the child and parent to cache the original unwrapped consumer first.
        assertTrue(root.getHandle(TestEvent::class.java).hasListener())
        ModuleEventCallbackTracker.instrument(moduleNode, scope)

        try {
            val dispatch = executor.submit { root.call(TestEvent()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { scope.quiesceEvents(Duration.ofMillis(50)) }

            release.countDown()
            dispatch.get(5, TimeUnit.SECONDS)
            scope.quiesceEvents(Duration.ofSeconds(1))
            root.call(TestEvent())
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            moduleClassLoader.close()
        }
    }

    @Test
    fun `instrumented expiring listeners retain Minestom result semantics`() {
        URLClassLoader(emptyArray(), javaClass.classLoader).use { moduleClassLoader ->
            val scope = ModuleResourceScope(moduleClassLoader)
            val node = EventNode.all("expiring-module")
            val calls = AtomicInteger()
            node.addListener(
                EventListener
                    .builder(TestEvent::class.java)
                    .expireCount(1)
                    .handler { calls.incrementAndGet() }
                    .build(),
            )
            ModuleEventCallbackTracker.instrument(node, scope)

            node.call(TestEvent())
            node.call(TestEvent())

            assertEquals(1, calls.get())
        }
    }

    private class TestEvent : Event
}
