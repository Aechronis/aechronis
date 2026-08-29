package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Coord
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class AutomaticChunkLeaseManager(
    private val unloadGraceMillis: Long = AUTOMATIC_CHUNK_UNLOAD_GRACE_MILLIS,
    private val cleanupRetryTicks: Int = AUTOMATIC_CHUNK_CLEANUP_RETRY_TICKS,
) {
    private val leasesByInstance = IdentityHashMap<Instance, MutableMap<Coord, AutomaticChunkLease>>()
    private val cleanupOperations = mutableSetOf<CompletableFuture<Unit>>()
    private var cleanupTask: Task? = null
    private var closed = false
    private var stopped = false

    internal fun isOpen(): Boolean = synchronized(leasesByInstance) { !closed }

    fun loadChunks(
        instance: Instance,
        session: DefenseSession,
        chunks: Collection<Coord>,
    ): CompletableFuture<Void> {
        val loads = mutableListOf<CompletableFuture<*>>()
        val newLeases = mutableListOf<AutomaticChunkLease>()
        synchronized(leasesByInstance) {
            check(!closed) { "Automatic chunk leases are shutting down" }
            val instanceLeases = leasesByInstance.getOrPut(instance, ::HashMap)
            chunks.distinct().forEach { coord ->
                var existingLease = instanceLeases[coord]
                if (
                    existingLease != null &&
                    existingLease.sessions.isEmpty() &&
                    (existingLease.loadFuture.isCancelled || existingLease.loadFuture.isCompletedExceptionally)
                ) {
                    instanceLeases.remove(coord)
                    existingLease = null
                }
                if (existingLease != null) {
                    existingLease.sessions.add(session)
                    existingLease.releasedAtMillis = 0
                    session.leasedAutomaticChunks.add(coord)
                    loads.add(existingLease.loadFuture)
                } else if (!instance.isChunkLoaded(coord.x, coord.z)) {
                    val lease = AutomaticChunkLease(instance, coord, instance.loadChunk(coord.x, coord.z))
                    lease.sessions.add(session)
                    session.leasedAutomaticChunks.add(coord)
                    instanceLeases[coord] = lease
                    loads.add(lease.loadFuture)
                    newLeases.add(lease)
                }
            }
            if (instanceLeases.isEmpty()) leasesByInstance.remove(instance)
        }
        newLeases.forEach { lease ->
            lease.loadFuture.whenComplete { _, _ ->
                val released = synchronized(leasesByInstance) { lease.sessions.isEmpty() }
                if (released) scheduleCleanup()
            }
        }
        return CompletableFuture.allOf(*loads.toTypedArray())
    }

    fun reconcile(session: DefenseSession) {
        val desiredChunks = buildSet {
            addAll(session.spawnPreparationChunks)
            session.slots.mapNotNull(AiDefenderSlot::defender).forEach { defender ->
                addAll(defender.navigation.chunks)
            }
        }
        val released = synchronized(leasesByInstance) {
            val instanceLeases = leasesByInstance[session.instance]
            val noLongerNeeded = session.leasedAutomaticChunks.filterNot(desiredChunks::contains)
            noLongerNeeded.mapNotNull { coord ->
                session.leasedAutomaticChunks.remove(coord)
                instanceLeases?.get(coord)?.also { lease ->
                    lease.sessions.remove(session)
                    if (lease.sessions.isEmpty()) lease.releasedAtMillis = System.currentTimeMillis()
                }
            }
        }
        if (released.isNotEmpty()) scheduleCleanup()
    }

    fun release(session: DefenseSession) {
        val released = synchronized(leasesByInstance) {
            val instanceLeases = leasesByInstance[session.instance]
            session.leasedAutomaticChunks.mapNotNull { coord ->
                instanceLeases?.get(coord)?.also { lease ->
                    lease.sessions.remove(session)
                    if (lease.sessions.isEmpty()) lease.releasedAtMillis = System.currentTimeMillis()
                }
            }.also { session.leasedAutomaticChunks.clear() }
        }
        if (released.isNotEmpty()) scheduleCleanup()
    }

    private fun scheduleCleanup() {
        synchronized(leasesByInstance) {
            if (closed) return
            if (cleanupTask != null) return
            cleanupTask = ModuleScheduler
                .buildTask(::cleanupReleasedChunks)
                .delay(TaskSchedule.tick(cleanupRetryTicks))
                .schedule()
        }
    }

    internal fun cleanupReleasedChunks() {
        val leases = synchronized(leasesByInstance) {
            cleanupTask?.cancel()
            cleanupTask = null
            leasesByInstance.values.flatMap { it.values }.filter { it.sessions.isEmpty() }
        }
        var retryNeeded = false
        leases.forEach { lease ->
            if (cleanupChunk(lease)) retryNeeded = true
        }
        if (retryNeeded) scheduleCleanup()
    }

    @Throws(InterruptedException::class, TimeoutException::class)
    fun prepareForShutdown(
        timeout: Long,
        unit: TimeUnit,
    ) {
        val (loads, cleanups) =
            synchronized(leasesByInstance) {
                closed = true
                cleanupTask?.cancel()
                cleanupTask = null
                val pendingLoads =
                    leasesByInstance.values.flatMap { leases -> leases.values.map(AutomaticChunkLease::loadFuture) }
                pendingLoads to cleanupOperations.toList()
            }
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        loads.forEach { load ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for automatic chunk loads")
            try {
                load.get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: ExecutionException) {
                // A completed failed load cannot leave a successfully loaded chunk to release.
            }
        }
        cleanups.forEach { cleanup ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for automatic chunk cleanup")
            cleanup.get(remaining, TimeUnit.NANOSECONDS)
        }
    }

    fun shutdown() {
        val leases =
            synchronized(leasesByInstance) {
                if (stopped) return
                stopped = true
                closed = true
                cleanupTask?.cancel()
                cleanupTask = null
                leasesByInstance.values.flatMap { it.values }.also { leasesByInstance.clear() }
            }
        var failure: Throwable? = null
        leases.forEach { lease ->
            lease.sessions.clear()
            if (!lease.loadFuture.isDone) {
                // Minestom chunk loads run independently of CompletableFuture cancellation. Retain
                // a one-shot cleanup so a late completion cannot become an untracked loaded chunk.
                lease.loadFuture.whenComplete { chunk, error ->
                    if (error == null && chunk != null) {
                        runCatching { releaseLoadedChunkAfterShutdown(lease, chunk) }
                            .onFailure { releaseError -> reportShutdownReleaseFailure(lease, releaseError) }
                    }
                }
                return@forEach
            }
            if (lease.loadFuture.isCancelled || lease.loadFuture.isCompletedExceptionally) return@forEach
            val chunk = lease.loadFuture.getNow(null) ?: return@forEach
            runCatching { releaseLoadedChunkAfterShutdown(lease, chunk) }
                .onFailure { error ->
                    reportShutdownReleaseFailure(lease, error)
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
        }
        failure?.let { throw it }
    }

    private fun releaseLoadedChunkAfterShutdown(
        lease: AutomaticChunkLease,
        chunk: Chunk,
    ) {
        if (lease.instance.getChunk(lease.coord.x, lease.coord.z) !== chunk) return
        val containsEntity = lease.instance.entities.any { entity ->
            entity.position.chunkX() == lease.coord.x && entity.position.chunkZ() == lease.coord.z
        }
        if (chunk.viewers.isNotEmpty() || containsEntity) return
        try {
            lease.instance.saveChunkToStorage(chunk).get(CHUNK_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while saving automatic chunk ${lease.coord} during module shutdown", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "Timed out after $CHUNK_SAVE_TIMEOUT_SECONDS seconds saving automatic chunk ${lease.coord} during module shutdown",
                error,
            )
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
        lease.instance.unloadChunk(chunk)
    }

    private fun reportShutdownReleaseFailure(
        lease: AutomaticChunkLease,
        error: Throwable,
    ) {
        System.err.println("Failed to release automatic chunk ${lease.coord} during module shutdown: ${error.message}")
    }

    // returns true when this lease is still ours but cannot be unloaded yet
    private fun cleanupChunk(lease: AutomaticChunkLease): Boolean {
        val cleanupOperation = CompletableFuture<Unit>()
        val loadedChunk =
            synchronized(leasesByInstance) {
                if (closed) return false
                val instanceLeases = leasesByInstance[lease.instance] ?: return false
                if (instanceLeases[lease.coord] !== lease || lease.sessions.isNotEmpty()) return false
                if (!lease.loadFuture.isDone) return false
                if (lease.loadFuture.isCancelled || lease.loadFuture.isCompletedExceptionally) {
                    removeLease(lease, instanceLeases)
                    return false
                }
                if (System.currentTimeMillis() - lease.releasedAtMillis < unloadGraceMillis) return true

                val leasedChunk = lease.loadFuture.getNow(null)
                val currentChunk = lease.instance.getChunk(lease.coord.x, lease.coord.z)
                if (currentChunk !== leasedChunk) {
                    removeLease(lease, instanceLeases)
                    return false
                }
                if (currentChunk.viewers.isNotEmpty() || lease.instance.containsEntityIn(lease.coord)) return true

                cleanupOperations.add(cleanupOperation)
                currentChunk
            }

        val saved =
            runCatching {
                lease.instance.saveChunkToStorage(loadedChunk).get(CHUNK_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.onFailure { error ->
                if (error is InterruptedException) Thread.currentThread().interrupt()
                System.err.println("Failed to save automatic chunk ${lease.coord} before unload: ${error.message}")
            }.isSuccess

        return try {
            synchronized(leasesByInstance) {
                val instanceLeases = leasesByInstance[lease.instance] ?: return@synchronized false
                if (instanceLeases[lease.coord] !== lease || lease.sessions.isNotEmpty()) return@synchronized false
                if (!saved) return@synchronized true

                val currentChunk = lease.instance.getChunk(lease.coord.x, lease.coord.z)
                if (currentChunk !== loadedChunk) {
                    removeLease(lease, instanceLeases)
                    return@synchronized false
                }
                if (currentChunk.viewers.isNotEmpty() || lease.instance.containsEntityIn(lease.coord)) {
                    return@synchronized true
                }
                lease.instance.unloadChunk(currentChunk)
                removeLease(lease, instanceLeases)
                false
            }
        } finally {
            synchronized(leasesByInstance) {
                cleanupOperations.remove(cleanupOperation)
                cleanupOperation.complete(Unit)
            }
        }
    }

    private fun Instance.containsEntityIn(coord: Coord): Boolean = entities.any { entity ->
        entity.position.chunkX() == coord.x && entity.position.chunkZ() == coord.z
    }

    private fun removeLease(
        lease: AutomaticChunkLease,
        instanceLeases: MutableMap<Coord, AutomaticChunkLease>,
    ) {
        instanceLeases.remove(lease.coord)
        if (instanceLeases.isEmpty()) leasesByInstance.remove(lease.instance)
    }

    private class AutomaticChunkLease(
        val instance: Instance,
        val coord: Coord,
        val loadFuture: CompletableFuture<Chunk>,
    ) {
        val sessions: MutableSet<DefenseSession> = mutableSetOf()
        var releasedAtMillis: Long = 0
    }

    private companion object {
        const val AUTOMATIC_CHUNK_UNLOAD_GRACE_MILLIS = 5_000L
        const val AUTOMATIC_CHUNK_CLEANUP_RETRY_TICKS = 100
        const val CHUNK_SAVE_TIMEOUT_SECONDS = 30L
    }
}
