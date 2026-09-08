package net.aechronis.vanilla.managers

import net.aechronis.vanilla.serdes.PlayerDataSerializer
import net.aechronis.vanilla.serdes.PlayerDataSnapshot
import net.kyori.adventure.nbt.BinaryTagIO
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Serializes immutable snapshots in submission order and retains unsaved state for reconnects. */
internal class PlayerDataWriter(
    private val path: Path,
    executor: Executor? = null,
    private val writeSnapshot: (Path, PlayerDataSnapshot) -> Unit = ::writePlayerData,
) : AutoCloseable {
    private class PendingSave(
        val snapshot: PlayerDataSnapshot,
        var failure: Exception? = null,
    )

    private val lock = Any()
    private val pending = linkedMapOf<UUID, PendingSave>()
    private val ownedExecutor =
        if (executor == null) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "aechronis-playerdata-writer").apply { isDaemon = true }
            }
        } else {
            null
        }
    private val executor = executor ?: checkNotNull(ownedExecutor)
    private var tail = CompletableFuture.completedFuture<Void>(null)
    private var closed = false

    fun pending(uuid: UUID): PlayerDataSnapshot? = synchronized(lock) { pending[uuid]?.snapshot }

    fun save(
        snapshots: Collection<PlayerDataSnapshot>,
        retryPending: Boolean = false,
    ): CompletableFuture<Void> =
        synchronized(lock) {
            if (closed) return CompletableFuture.failedFuture(RejectedExecutionException("Player data writer is closed"))

            val submitted = linkedMapOf<UUID, PendingSave>()
            snapshots.forEach { snapshot ->
                val entry = PendingSave(snapshot)
                pending[snapshot.uuid] = entry
                submitted[snapshot.uuid] = entry
            }
            val batch = (if (retryPending) pending.values else submitted.values).toList()
            tail =
                try {
                    tail.handle { _, _ -> null }.thenRunAsync({ writeBatch(batch) }, executor)
                } catch (error: RejectedExecutionException) {
                    CompletableFuture.failedFuture(error)
                }
            // Cancelling a caller's wait must never let subsequent writes overtake this batch.
            tail.copy()
        }

    private fun writeBatch(batch: List<PendingSave>) {
        val failures = mutableListOf<Exception>()
        batch.forEach { entry ->
            val uuid = entry.snapshot.uuid
            if (!synchronized(lock) { pending[uuid] === entry }) return@forEach
            try {
                writeSnapshot(path, entry.snapshot)
                synchronized(lock) {
                    if (pending[uuid] === entry) pending.remove(uuid)
                }
            } catch (error: Exception) {
                val failure = IOException("Failed to save player data for $uuid", error)
                synchronized(lock) { entry.failure = failure }
                failures.add(failure)
            }
        }
        if (failures.isNotEmpty()) {
            throw IOException("Failed to save ${failures.size} player data snapshot(s)").also { failure ->
                failures.forEach(failure::addSuppressed)
            }
        }
    }

    /** Rejects further submissions and drains every accepted batch, including after a failure. */
    override fun close() {
        val last =
            synchronized(lock) {
                closed = true
                tail
            }
        val queueFailure =
            try {
                last.handle { _, error -> error }.join()
            } finally {
                ownedExecutor?.shutdown()
            }
        val unsaved = synchronized(lock) { pending.values.toList() }
        if (unsaved.isNotEmpty()) {
            throw IOException("Could not save player data for ${unsaved.size} player(s) before closing", queueFailure).also { failure ->
                unsaved.forEach { entry ->
                    failure.addSuppressed(entry.failure ?: IOException("Unsaved player data for ${entry.snapshot.uuid}"))
                }
            }
        }
    }
}

private fun writePlayerData(
    path: Path,
    snapshot: PlayerDataSnapshot,
) {
    val data = PlayerDataSerializer.serialize(snapshot)
    Files.createDirectories(path)
    val target = path.resolve("${snapshot.uuid}.dat")
    val temporary = Files.createTempFile(path, ".${snapshot.uuid}.", ".tmp")
    try {
        Files.newOutputStream(temporary).use { output ->
            BinaryTagIO.writer().writeNamed(SimpleImmutableEntry("", data), output, BinaryTagIO.Compression.GZIP)
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
