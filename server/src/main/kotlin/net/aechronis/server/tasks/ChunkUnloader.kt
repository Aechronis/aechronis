package net.aechronis.server.tasks

import net.aechronis.server.Server
import net.aechronis.server.world.TrackedChunk
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.CoordConversion
import net.minestom.server.entity.Player
import net.minestom.server.instance.EntityTracker
import net.minestom.server.property.ServerProperties
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Unloads primary-world chunks that nothing has needed for a while. It never writes: a modified
 * chunk stays loaded until a [WorldSaver] checkpoint has saved it, so chunks still reach disk only
 * after the module state they belong with.
 */
object ChunkUnloader {
    private const val SCAN_INTERVAL_TICKS = 100
    private const val UNLOAD_BUDGET_NANOS = 2_000_000L

    // An entity may read blocks just across a chunk border.
    private const val ENTITY_RANGE = 1

    // Minestom recentres a client's chunks only when the player enters a chunk outside their
    // recent history, so the square a client holds can trail the player by half that history.
    // Unloading a chunk the client still holds would leave the player unable to walk into it.
    private val clientViewLag = ServerProperties.PLAYER_CHUNK_UPDATE_LIMITER_HISTORY_SIZE.get() / 2 + 1

    private val graceSeconds = System.getProperty("aechronis.world.unloadGraceSeconds")?.toInt() ?: 30
    private val graceScans = (graceSeconds * 20 + SCAN_INTERVAL_TICKS - 1) / SCAN_INTERVAL_TICKS

    private class Occupant(
        val chunkX: Int,
        val chunkZ: Int,
        val range: Int,
    )

    private val retained = HashMap<Long, Int>()
    private val pending = ArrayDeque<TrackedChunk>()
    private var task: Task? = null
    private var ticksUntilScan = SCAN_INTERVAL_TICKS
    private var scanning = false

    fun start() {
        // A grace of zero or less keeps every chunk loaded, as before chunks could unload.
        if (graceScans <= 0) return
        check(task == null) { "ChunkUnloader is already started" }
        task =
            MinecraftServer
                .getSchedulerManager()
                .buildTask(::tick)
                .repeat(TaskSchedule.tick(1))
                .schedule()
    }

    fun shutdown() {
        task?.cancel()
        task = null
    }

    /**
     * Keeps a primary-world chunk loaded until the returned handle is closed. Once this returns the
     * chunk can no longer be unloaded, so retain first and load second.
     */
    fun retain(
        chunkX: Int,
        chunkZ: Int,
    ): AutoCloseable {
        val index = CoordConversion.chunkIndex(chunkX, chunkZ)
        synchronized(retained) { retained[index] = (retained[index] ?: 0) + 1 }
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                synchronized(retained) { retained.computeIfPresent(index) { _, holds -> if (holds > 1) holds - 1 else null } }
            }
        }
    }

    private fun tick() {
        if (--ticksUntilScan <= 0 && !scanning) {
            ticksUntilScan = SCAN_INTERVAL_TICKS
            startScan()
        }
        if (pending.isNotEmpty()) unloadPending()
    }

    // Walking every loaded chunk is too slow for the tick thread. The scan therefore works from a
    // copy of where entities are, and each chunk it reports is checked again before it unloads.
    private fun startScan() {
        val occupants =
            Server.instance.entities.map { entity ->
                val position = entity.position
                Occupant(position.chunkX(), position.chunkZ(), range(entity as? Player))
            }
        val held = synchronized(retained) { retained.keys.toList() }
        scanning = true
        Thread.startVirtualThread {
            val idle =
                try {
                    findIdleChunks(occupants, held)
                } catch (error: Throwable) {
                    MinecraftServer.getExceptionManager().handleException(error)
                    emptyList()
                }
            MinecraftServer.getSchedulerManager().scheduleNextTick {
                scanning = false
                for (chunk in idle) {
                    if (chunk.queuedForUnload) continue
                    chunk.queuedForUnload = true
                    pending.addLast(chunk)
                }
            }
        }
    }

    private fun findIdleChunks(
        occupants: List<Occupant>,
        held: List<Long>,
    ): List<TrackedChunk> {
        val needed = HashSet(held)
        for (occupant in occupants) {
            for (chunkX in occupant.chunkX - occupant.range..occupant.chunkX + occupant.range) {
                for (chunkZ in occupant.chunkZ - occupant.range..occupant.chunkZ + occupant.range) {
                    needed.add(CoordConversion.chunkIndex(chunkX, chunkZ))
                }
            }
        }

        val idle = ArrayList<TrackedChunk>()
        for (chunk in Server.instance.chunks) {
            if (chunk !is TrackedChunk) continue
            if (CoordConversion.chunkIndex(chunk.chunkX, chunk.chunkZ) in needed) {
                chunk.idleScans = 0
            } else if (++chunk.idleScans > graceScans && !chunk.needsSave) {
                idle += chunk
            }
        }
        return idle
    }

    private fun unloadPending() {
        val instance = Server.instance
        val startedAt = System.nanoTime()
        val players =
            instance.players.map { player ->
                val position = player.position
                Occupant(position.chunkX(), position.chunkZ(), range(player))
            }
        while (true) {
            val chunk = pending.removeFirstOrNull() ?: break
            chunk.queuedForUnload = false
            val chunkX = chunk.chunkX
            val chunkZ = chunk.chunkZ
            if (instance.getChunk(chunkX, chunkZ) === chunk &&
                players.none { abs(chunkX - it.chunkX) <= it.range && abs(chunkZ - it.chunkZ) <= it.range } &&
                !hasEntityNearby(chunkX, chunkZ) &&
                !chunk.needsSave
            ) {
                val index = CoordConversion.chunkIndex(chunkX, chunkZ)
                // Held across the unload so that retain() never returns for a chunk on its way out.
                synchronized(retained) {
                    if (!retained.containsKey(index)) unload(chunk)
                }
            }
            if (System.nanoTime() - startedAt >= UNLOAD_BUDGET_NANOS) break
        }
    }

    // Minestom cancels a repeating task whose callback throws, which would end unloading for good.
    private fun unload(chunk: TrackedChunk) {
        try {
            Server.instance.unloadChunk(chunk)
        } catch (error: Throwable) {
            MinecraftServer.getExceptionManager().handleException(
                RuntimeException("[World] Failed to unload chunk ${chunk.chunkX}, ${chunk.chunkZ}", error),
            )
        }
    }

    private fun hasEntityNearby(
        chunkX: Int,
        chunkZ: Int,
    ): Boolean {
        val tracker = Server.instance.entityTracker
        for (x in chunkX - ENTITY_RANGE..chunkX + ENTITY_RANGE) {
            for (z in chunkZ - ENTITY_RANGE..chunkZ + ENTITY_RANGE) {
                if (tracker.chunkEntities(x, z, EntityTracker.Target.ENTITIES).isNotEmpty()) return true
            }
        }
        return false
    }

    private fun range(player: Player?): Int = if (player == null) ENTITY_RANGE else player.effectiveViewDistance() + clientViewLag
}
