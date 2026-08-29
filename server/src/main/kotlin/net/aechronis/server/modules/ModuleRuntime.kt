package net.aechronis.server.modules

import net.aechronis.server.Server
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.lang.StackWalker
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.concurrent.withLock

internal class ModuleResourceScope(
    val classLoader: ClassLoader,
    private val asyncExecutor: Executor = ForkJoinPool.commonPool(),
) {
    val eventNode: EventNode<Event> = EventNode.all("module-generation")

    private val tasks = linkedSetOf<TrackedTask>()
    private val attachedEventNodes = Collections.newSetFromMap(IdentityHashMap<EventNode<out Event>, Boolean>())
    private val lifecycleLock = Any()
    private val eventLock = ReentrantLock()
    private val eventsIdle = eventLock.newCondition()
    private var acceptingTasks = true
    private var acceptingEvents = true
    private var activeEvents = 0

    fun <E : Event> addListener(
        eventType: Class<E>,
        listener: Consumer<E>,
    ) {
        eventLock.withLock {
            check(acceptingEvents) { "Module generation is unloading" }
            eventNode.addListener(eventType) { event ->
                dispatchEvent(Runnable { listener.accept(event) })
            }
        }
    }

    fun <T : Event> addChild(
        parent: EventNode<T>,
        child: EventNode<out T>,
    ) {
        ModuleEventCallbackTracker.instrument(child, this)
        parent.addChild(child)
        synchronized(lifecycleLock) {
            attachedEventNodes += child
        }
    }

    fun ownsEventNode(node: EventNode<out Event>): Boolean = synchronized(lifecycleLock) { node in attachedEventNodes }

    private fun dispatchEvent(callback: Runnable) {
        dispatchCallback(Unit) { callback.run() }
    }

    internal fun <T> dispatchCallback(
        rejected: T,
        callback: () -> T,
    ): T {
        eventLock.withLock {
            if (!acceptingEvents) return rejected
            activeEvents += 1
        }

        return try {
            withContextClassLoader(classLoader, callback)
        } finally {
            completeCallback()
        }
    }

    /**
     * Reserves lifecycle work before handing a module callback to another thread. Reserving first
     * ensures quiescence also waits for callbacks that are queued but have not started yet.
     */
    fun runAsync(callback: Runnable): CompletableFuture<Void> {
        eventLock.withLock {
            if (!acceptingEvents) {
                return CompletableFuture.failedFuture(
                    RejectedExecutionException("Module generation is unloading"),
                )
            }
            activeEvents += 1
        }

        return try {
            val operation =
                CompletableFuture.runAsync(
                    {
                        try {
                            withContextClassLoader(classLoader, callback::run)
                        } finally {
                            completeCallback()
                        }
                    },
                    asyncExecutor,
                )
            // Cancellation of a caller-facing future must not cancel the executor's reserved work;
            // only lifecycle quiescence owns that reservation and its final decrement.
            operation.minimalCompletionStage().toCompletableFuture()
        } catch (error: Throwable) {
            completeCallback()
            CompletableFuture.failedFuture(error)
        }
    }

    private fun completeCallback() {
        eventLock.withLock {
            activeEvents -= 1
            if (activeEvents == 0) eventsIdle.signalAll()
        }
    }

    fun quiesceEvents(timeout: Duration = EVENT_QUIESCE_TIMEOUT) {
        require(!timeout.isNegative && !timeout.isZero) { "Event quiescence timeout must be positive" }
        eventLock.withLock {
            acceptingEvents = false
            var remaining = timeout.toNanos()
            while (activeEvents != 0) {
                if (remaining <= 0L) {
                    throw TimeoutException("Module events did not quiesce within $timeout")
                }
                try {
                    remaining = eventsIdle.awaitNanos(remaining)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while waiting for module events to quiesce", error)
                }
            }
        }
    }

    fun track(
        task: Task,
        callback: DetachableSchedule,
    ) {
        val stale = mutableListOf<TrackedTask>()
        val accepted =
            synchronized(lifecycleLock) {
                tasks.filterTo(stale) { !it.task.isAlive }
                tasks.removeAll(stale)
                if (acceptingTasks && !callback.isDetached()) {
                    tasks += TrackedTask(task, callback)
                    true
                } else {
                    false
                }
            }
        stale.forEach { it.callback.detach() }
        if (!accepted) {
            callback.detach()
            task.cancel()
        }
    }

    fun invoke(callback: Supplier<TaskSchedule>): TaskSchedule {
        if (!synchronized(lifecycleLock) { acceptingTasks }) return TaskSchedule.stop()
        return dispatchCallback(TaskSchedule.stop(), callback::get)
    }

    fun cancelTasks(): List<String> {
        val scheduled =
            synchronized(lifecycleLock) {
                acceptingTasks = false
                tasks.toList().also { tasks.clear() }
            }
        val failures = mutableListOf<String>()
        scheduled.forEach { tracked ->
            tracked.callback.detach()
            runCatching(tracked.task::cancel).onFailure { error ->
                failures += error.message ?: error.javaClass.simpleName
            }
        }
        return failures
    }

    private data class TrackedTask(
        val task: Task,
        val callback: DetachableSchedule,
    )

    private companion object {
        val EVENT_QUIESCE_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}

internal object ModuleRuntime {
    private val stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    private val scopesByClassLoader = ConcurrentHashMap<ClassLoader, ModuleResourceScope>()

    @Volatile
    private var managedRuntime = false

    @Volatile
    private var activeScope: ModuleResourceScope? = null

    fun activate(scope: ModuleResourceScope) {
        check(activeScope == null) { "A module generation is already active" }
        check(scopesByClassLoader.putIfAbsent(scope.classLoader, scope) == null) { "Module classloader is already active" }
        var blocksActivated = false
        var eventNodeAttached = false
        try {
            ModuleBlocks.activate(scope)
            blocksActivated = true
            Server.eventNode.addChild(scope.eventNode)
            eventNodeAttached = true
            managedRuntime = true
            activeScope = scope
        } catch (error: Throwable) {
            if (eventNodeAttached) runCatching { Server.eventNode.removeChild(scope.eventNode) }
            if (blocksActivated) runCatching { ModuleBlocks.deactivate(scope) }
            scopesByClassLoader.remove(scope.classLoader, scope)
            throw error
        }
    }

    fun deactivate(scope: ModuleResourceScope) {
        Server.eventNode.removeChild(scope.eventNode)
        ModuleBlocks.deactivate(scope)
        scopesByClassLoader.remove(scope.classLoader, scope)
        if (activeScope === scope) activeScope = null
    }

    fun captureScope(): ModuleResourceScope? {
        Thread.currentThread().contextClassLoader?.let { loader ->
            scopesByClassLoader[loader]?.let { return it }
        }
        var owner: ModuleResourceScope? = null
        stackWalker.forEach { frame ->
            if (owner == null) frame.declaringClass.classLoader?.let { owner = scopesByClassLoader[it] }
        }
        return owner
    }

    fun track(
        owner: ModuleResourceScope?,
        task: Task,
        callback: DetachableSchedule,
    ) {
        if (owner == null) {
            // Unit tests and standalone consumers can use the facade without a ModuleManager.
            if (!managedRuntime) return
            callback.detach()
            task.cancel()
            return
        }
        owner.track(task, callback)
    }

    fun <E : Event> addListener(
        eventType: Class<E>,
        listener: Consumer<E>,
    ) {
        captureScope()?.addListener(eventType, listener) ?: error("No module generation is active")
    }

    fun <T : Event> addChild(
        parent: EventNode<T>,
        child: EventNode<out T>,
    ) {
        val scope = captureScope()
        if (scope == null) {
            // Unit tests and standalone consumers do not have a managed generation to quiesce.
            check(!managedRuntime) { "No module generation is active" }
            parent.addChild(child)
            return
        }
        scope.addChild(parent, child)
    }

    fun runAsync(callback: Runnable): CompletableFuture<Void> {
        val scope = captureScope()
        if (scope != null) return scope.runAsync(callback)
        if (!managedRuntime) return CompletableFuture.runAsync(callback)
        return CompletableFuture.failedFuture(
            RejectedExecutionException("No module generation is active"),
        )
    }

    fun isManagedRuntime(): Boolean = managedRuntime
}

internal class DetachableSchedule(
    callback: Supplier<TaskSchedule>,
    owner: ModuleResourceScope?,
) : Supplier<TaskSchedule> {
    @Volatile
    private var state: State? = State(callback, owner)

    override fun get(): TaskSchedule {
        val current = state ?: return TaskSchedule.stop()
        val schedule =
            if (current.owner == null) {
                current.callback.get()
            } else {
                current.owner.invoke(current.callback)
            }
        if (schedule === TaskSchedule.stop()) detach()
        return schedule
    }

    fun isDetached(): Boolean = state == null

    fun detach() {
        // Minestom can retain a cancelled delayed task in its timing queue. Clear the complete
        // state so that queue entry cannot pin either the module callback or its classloader.
        state = null
    }

    private data class State(
        val callback: Supplier<TaskSchedule>,
        val owner: ModuleResourceScope?,
    )
}

/**
 * Scheduler facade used by runtime modules. Its core-owned callback wrapper is important: merely
 * cancelling a Minestom task does not remove its callback from delayed scheduler queues.
 */
object ModuleScheduler {
    fun submitTask(task: Supplier<TaskSchedule>): Task = submitTask(task, ModuleRuntime.captureScope())

    fun buildTask(task: Runnable): ModuleTaskBuilder = ModuleTaskBuilder(task, ModuleRuntime.captureScope())

    fun scheduleNextTick(task: Runnable): Task =
        ModuleTaskBuilder(task, ModuleRuntime.captureScope())
            .delay(TaskSchedule.nextTick())
            .schedule()

    internal fun submitTask(
        task: Supplier<TaskSchedule>,
        owner: ModuleResourceScope?,
    ): Task {
        // SchedulerManager invokes suppliers once before returning the Task. Reject an
        // unowned call before handing it the module callback, otherwise a late callback from a
        // closed classloader could still execute once and attach work to the new generation.
        if (owner == null && ModuleRuntime.isManagedRuntime()) {
            return MinecraftServer.getSchedulerManager().submitTask { TaskSchedule.stop() }
        }

        val detachable = DetachableSchedule(task, owner)
        val scheduled = MinecraftServer.getSchedulerManager().submitTask(detachable)
        ModuleRuntime.track(owner, scheduled, detachable)
        return scheduled
    }
}

/** Runs module-owned background work while keeping it inside generation quiescence. */
object ModuleAsync {
    fun runAsync(callback: Runnable): CompletableFuture<Void> = ModuleRuntime.runAsync(callback)
}

class ModuleTaskBuilder internal constructor(
    private val task: Runnable,
    private val owner: ModuleResourceScope?,
) {
    private var delay: TaskSchedule = TaskSchedule.immediate()
    private var repeat: TaskSchedule? = null

    fun delay(schedule: TaskSchedule): ModuleTaskBuilder = apply { delay = schedule }

    fun repeat(schedule: TaskSchedule): ModuleTaskBuilder = apply { repeat = schedule }

    fun schedule(): Task {
        var first = true
        val callback =
            Supplier {
                if (first) {
                    first = false
                    delay
                } else {
                    task.run()
                    repeat ?: TaskSchedule.stop()
                }
            }
        return ModuleScheduler.submitTask(callback, owner)
    }
}

internal inline fun <T> withContextClassLoader(
    classLoader: ClassLoader,
    action: () -> T,
): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = classLoader
    return try {
        action()
    } finally {
        thread.contextClassLoader = previous
    }
}
