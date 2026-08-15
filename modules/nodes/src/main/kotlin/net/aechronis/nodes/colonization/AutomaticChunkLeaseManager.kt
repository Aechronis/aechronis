package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Coord
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

internal class AutomaticChunkLeaseManager {
    private val leasesByInstance = IdentityHashMap<Instance, MutableMap<Coord, AutomaticChunkLease>>()
    private var cleanupTask: Task? = null

    fun loadChunks(
        instance: Instance,
        session: DefenseSession,
        chunks: Collection<Coord>,
    ): CompletableFuture<Void> {
        val loads = mutableListOf<CompletableFuture<*>>()
        val newLeases = mutableListOf<AutomaticChunkLease>()
        synchronized(leasesByInstance) {
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
            if (cleanupTask != null) return
            cleanupTask = MinecraftServer.getSchedulerManager()
                .buildTask(::cleanupReleasedChunks)
                .delay(TaskSchedule.tick(AUTOMATIC_CHUNK_CLEANUP_RETRY_TICKS))
                .schedule()
        }
    }

    private fun cleanupReleasedChunks() {
        val leases = synchronized(leasesByInstance) {
            cleanupTask = null
            leasesByInstance.values.flatMap { it.values }.filter { it.sessions.isEmpty() }
        }
        var retryNeeded = false
        leases.forEach { lease ->
            if (cleanupChunk(lease)) retryNeeded = true
        }
        if (retryNeeded) scheduleCleanup()
    }

    // returns true when this lease is still ours but cannot be unloaded yet
    private fun cleanupChunk(lease: AutomaticChunkLease): Boolean = synchronized(leasesByInstance) {
        val instanceLeases = leasesByInstance[lease.instance] ?: return@synchronized false
        if (instanceLeases[lease.coord] !== lease || lease.sessions.isNotEmpty()) return@synchronized false
        if (!lease.loadFuture.isDone) return@synchronized false
        if (lease.loadFuture.isCancelled || lease.loadFuture.isCompletedExceptionally) {
            removeLease(lease, instanceLeases)
            return@synchronized false
        }
        if (System.currentTimeMillis() - lease.releasedAtMillis < AUTOMATIC_CHUNK_UNLOAD_GRACE_MILLIS) {
            return@synchronized true
        }

        val leasedChunk = lease.loadFuture.getNow(null)
        val loadedChunk = lease.instance.getChunk(lease.coord.x, lease.coord.z)
        if (loadedChunk !== leasedChunk) {
            removeLease(lease, instanceLeases)
            return@synchronized false
        }
        val containsEntity = lease.instance.entities.any { entity ->
            entity.position.chunkX() == lease.coord.x && entity.position.chunkZ() == lease.coord.z
        }
        if (loadedChunk.viewers.isNotEmpty() || containsEntity) return@synchronized true

        val unloaded = runCatching { lease.instance.unloadChunk(loadedChunk) }.isSuccess
        if (unloaded) removeLease(lease, instanceLeases)
        !unloaded
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
    }
}
