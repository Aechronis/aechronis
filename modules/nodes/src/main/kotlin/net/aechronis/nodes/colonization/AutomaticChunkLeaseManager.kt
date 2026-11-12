package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Coord
import net.aechronis.server.tasks.ChunkUnloader
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Keeps the chunks a defense session works in loaded while no player is near them. A released
 * chunk is left to the server's [ChunkUnloader], which unloads it once it is saved and unused.
 */
internal class AutomaticChunkLeaseManager {
    private val leasesByInstance = IdentityHashMap<Instance, MutableMap<Coord, AutomaticChunkLease>>()
    private var closed = false

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
                // A chunk a player happened to load is leased as well: it unloads once they leave.
                val lease =
                    instanceLeases.getOrPut(coord) {
                        // Retained before loading, so the chunk cannot unload in between.
                        val retention = ChunkUnloader.retain(coord.x, coord.z)
                        AutomaticChunkLease(instance, coord, retention, instance.loadChunk(coord.x, coord.z)).also(newLeases::add)
                    }
                lease.sessions.add(session)
                session.leasedAutomaticChunks.add(coord)
                loads.add(lease.loadFuture)
            }
        }
        newLeases.forEach { lease ->
            // A lease released while still loading is kept until then for prepareForShutdown.
            lease.loadFuture.whenComplete { _, _ ->
                synchronized(leasesByInstance) { removeIfUnused(lease) }
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
        synchronized(leasesByInstance) {
            val instanceLeases = leasesByInstance[session.instance]
            session.leasedAutomaticChunks.filterNot(desiredChunks::contains).forEach { coord ->
                session.leasedAutomaticChunks.remove(coord)
                instanceLeases?.get(coord)?.let { lease -> release(lease, session) }
            }
        }
    }

    fun release(session: DefenseSession) {
        synchronized(leasesByInstance) {
            val instanceLeases = leasesByInstance[session.instance]
            session.leasedAutomaticChunks.forEach { coord ->
                instanceLeases?.get(coord)?.let { lease -> release(lease, session) }
            }
            session.leasedAutomaticChunks.clear()
        }
    }

    @Throws(InterruptedException::class, TimeoutException::class)
    fun prepareForShutdown(
        timeout: Long,
        unit: TimeUnit,
    ) {
        val loads =
            synchronized(leasesByInstance) {
                closed = true
                leasesByInstance.values.flatMap { leases -> leases.values.map(AutomaticChunkLease::loadFuture) }
            }
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        loads.forEach { load ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for automatic chunk loads")
            try {
                load.get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: ExecutionException) {
                // A failed load leaves no chunk behind.
            }
        }
    }

    fun shutdown() {
        val leases =
            synchronized(leasesByInstance) {
                closed = true
                leasesByInstance.values.flatMap { it.values }.also { leasesByInstance.clear() }
            }
        leases.forEach { lease -> lease.retention.close() }
    }

    private fun release(
        lease: AutomaticChunkLease,
        session: DefenseSession,
    ) {
        lease.sessions.remove(session)
        removeIfUnused(lease)
    }

    private fun removeIfUnused(lease: AutomaticChunkLease) {
        if (lease.sessions.isNotEmpty() || !lease.loadFuture.isDone) return
        val instanceLeases = leasesByInstance[lease.instance] ?: return
        if (!instanceLeases.remove(lease.coord, lease)) return
        if (instanceLeases.isEmpty()) leasesByInstance.remove(lease.instance)
        lease.retention.close()
    }

    private class AutomaticChunkLease(
        val instance: Instance,
        val coord: Coord,
        val retention: AutoCloseable,
        val loadFuture: CompletableFuture<Chunk>,
    ) {
        val sessions: MutableSet<DefenseSession> = mutableSetOf()
    }
}
