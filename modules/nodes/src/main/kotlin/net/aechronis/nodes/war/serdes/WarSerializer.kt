/**
 * Handle saving war state
 * JSON Format:
 * {
 *   "war": true,            // flag for war enabled/disabled
 *   "occupied": {           // chunks occupied by a town
 *     "town1": [            // town occupying a chunk
 *        0, 1,              // interleaved chunk buffer [x0, y0, x1, y1, ...]
 *        2, 3 ],
 *     "town2": [
 *        4, 5,
 *        6, 7 ]
 *   },
 *   "colonized": [0, 1],   // occupied colony chunks
 *   "territoryOccupations": {
 *     "12": {"owner":"town-uuid","colonized":false}
 *   },
 *   "skirmishTargets": {"nation-uuid":12},
 *   "defeatedTowns": ["town-uuid"],
 *   "townLives": {"town-uuid":{"lives":1,"capitalGranted":true,"revision":2}},
 *   "attacks": [            // ongoing war, colony, and warzone flags
 *     {"id":"resident-uuid","c":[0,1],"b":[0,64,16],"m":"WAR","t":123},
 *     {attackJsonObject1},
 *     ...
 *   ]
 * }
 */

package net.aechronis.nodes.war.serdes

import com.google.gson.JsonPrimitive
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.war.FlagWar
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object WarSerializer {
    private var pendingWrite: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    private var acceptingAsyncWrites = true

    // snapshot mutable war state before dispatching any asynchronous file write
    fun save(async: Boolean): CompletableFuture<Void> {
        if (async) {
            val write = synchronized(this) {
                if (!acceptingAsyncWrites) {
                    return CompletableFuture.failedFuture(
                        IllegalStateException("The war-state writer is preparing for shutdown"),
                    )
                }
                val json = createJsonSnapshot()
                pendingWrite.handle { _, _ -> null }.thenRunAsync {
                    writeSnapshot(Nodes.config.pathWar, json)
                }.also { pendingWrite = it }
            }
            write.exceptionally { error ->
                System.err.println("Failed to save war state: ${error.message}")
                null
            }
            return write
        }

        val (reservation, json) = reserveSynchronousWrite()
        return try {
            writeSnapshot(Nodes.config.pathWar, json)
            reservation.complete(null)
            reservation
        } catch (error: Throwable) {
            reservation.completeExceptionally(error)
            throw error
        }
    }

    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    internal fun awaitIdle(
        timeout: Long,
        unit: TimeUnit,
    ) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            val observed = synchronized(this) { pendingWrite }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for the war-state writer")
            observed.get(remaining, TimeUnit.NANOSECONDS)
            if (synchronized(this) { pendingWrite === observed }) return
        }
    }

    internal fun resume() = synchronized(this) {
        acceptingAsyncWrites = true
    }

    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    internal fun prepareForShutdown(
        timeout: Long,
        unit: TimeUnit,
    ) {
        synchronized(this) { acceptingAsyncWrites = false }
        awaitQuiescence(timeout, unit)
    }

    private fun awaitQuiescence(
        timeout: Long,
        unit: TimeUnit,
    ) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            val observed = synchronized(this) { pendingWrite }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for the war-state writer")
            observed.handle { _, _ -> null }.get(remaining, TimeUnit.NANOSECONDS)
            if (synchronized(this) { pendingWrite === observed }) return
        }
    }

    private fun reserveSynchronousWrite(): SynchronousWrite {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WRITE_TIMEOUT_SECONDS)
        try {
            while (true) {
                val observed = synchronized(this) { pendingWrite }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) throw TimeoutException("Timed out waiting for an earlier war-state write")
                observed.handle { _, _ -> null }.get(remaining, TimeUnit.NANOSECONDS)

                synchronized(this) {
                    if (pendingWrite === observed) {
                        val json = createJsonSnapshot()
                        val reservation = CompletableFuture<Void>()
                        pendingWrite = reservation
                        return SynchronousWrite(reservation, json)
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting to save war state", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "An earlier war-state write did not finish within $WRITE_TIMEOUT_SECONDS seconds",
                error,
            )
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private data class SynchronousWrite(
        val reservation: CompletableFuture<Void>,
        val json: String,
    )

    internal fun createJsonSnapshot(): String {
        val occupiedByTown = linkedMapOf<String, MutableList<Int>>()
        FlagWar.occupiedChunks.forEach { coord ->
            val chunk = TerritoryChunk.fromCoord(coord) ?: return@forEach
            val townId = chunk.occupier?.uuid?.toString() ?: return@forEach
            val coordinates = occupiedByTown.getOrPut(townId, ::mutableListOf)
            coordinates.add(coord.x)
            coordinates.add(coord.z)
        }
        val colonized = buildString {
            append('[')
            FlagWar.colonizedChunks.forEachIndexed { index, coord ->
                if (index > 0) append(',')
                append(coord.x).append(',').append(coord.z)
            }
            append(']')
        }
        val territoryOccupations = FlagWar.territoryOccupations.entries
            .sortedBy { (territoryId, _) -> territoryId.toInt() }
            .joinToString(",") { (territoryId, occupation) ->
                val owner = occupation.occupierId?.let { JsonPrimitive(it.toString()).toString() } ?: "null"
                "${JsonPrimitive(territoryId.toInt().toString())}:{\"owner\":$owner,\"colonized\":${occupation.colonized}}"
            }
        val attacks = FlagWar.chunkToAttacker.values
            .map { it.toJson().toString() }
        val skirmishTargets = FlagWar.skirmishTargetsByNation.entries
            .sortedBy { (nationId, _) -> nationId.toString() }
            .joinToString(",") { (nationId, territoryId) ->
                "${JsonPrimitive(nationId.toString())}:${territoryId.toInt()}"
            }
        val defeatedTowns = FlagWar.townsDefeatedThisWar
            .sortedBy(UUID::toString)
            .joinToString(",") { townId -> JsonPrimitive(townId.toString()).toString() }
        val townLives = Nodes.towns.values
            .sortedBy { town -> town.uuid.toString() }
            .joinToString(",") { town ->
                "${JsonPrimitive(town.uuid.toString())}:{\"lives\":${town.lives}," +
                    "\"capitalGranted\":${town.capitalLifeGranted},\"revision\":${town.lifeRevision}}"
            }

        return buildString {
            append("{\"war\":${FlagWar.enabled},")
            append("\"flagAnnex\":${FlagWar.canAnnexTerritories},")
            append("\"flagBordersOnly\":${FlagWar.canOnlyAttackBorders},")
            append("\"flagDestruction\":${FlagWar.destructionEnabled},")
            append("\"flagDeathWar\":${FlagWar.isDeathWar},")
            append("\"occupied\":{")
            append(
                occupiedByTown.entries.joinToString(",") { (townId, coordinates) ->
                    "${JsonPrimitive(townId)}:${coordinates.joinToString(",", "[", "]")}"
                },
            )
            append("},\"colonized\":$colonized,")
            append("\"territoryOccupations\":{$territoryOccupations},")
            append("\"skirmishTargets\":{$skirmishTargets},")
            append("\"defeatedTowns\":[$defeatedTowns],")
            append("\"townLives\":{$townLives},")
            append("\"attacks\":[${attacks.joinToString(",")}]}")
        }
    }

    private fun writeSnapshot(
        path: Path,
        json: String,
    ) {
        val absolutePath = path.toAbsolutePath()
        val parent = absolutePath.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${absolutePath.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    temporary,
                    absolutePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absolutePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private const val WRITE_TIMEOUT_SECONDS = 60L
}
