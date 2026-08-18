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
 *   "atttacks": [           // ongoing attacks
 *     {attackJsonObject0},
 *     {attackJsonObject1},
 *     ...
 *   ]
 * }
 */

package net.aechronis.nodes.war.serdes

import com.google.gson.JsonPrimitive
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.war.AttackMode
import net.aechronis.nodes.war.FlagWar
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture

object WarSerializer {
    private var pendingWrite: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    // snapshot mutable war state before dispatching any asynchronous file write
    @Synchronized
    fun save(async: Boolean): CompletableFuture<Void> {
        val json = createJsonSnapshot()
        if (async) {
            pendingWrite = pendingWrite.handle { _, _ -> null }.thenRunAsync {
                writeSnapshot(Nodes.config.pathWar, json)
            }
            pendingWrite.exceptionally { error ->
                System.err.println("Failed to save war state: ${error.message}")
                null
            }
            return pendingWrite
        } else {
            pendingWrite.handle { _, _ -> null }.join()
            writeSnapshot(Nodes.config.pathWar, json)
            pendingWrite = CompletableFuture.completedFuture(null)
            return pendingWrite
        }
    }

    private fun createJsonSnapshot(): String {
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
            .filter { it.mode == AttackMode.WAR }
            .map { it.toJson().toString() }

        return buildString {
            append("{\"war\":${FlagWar.enabled},")
            append("\"flagAnnex\":${FlagWar.canAnnexTerritories},")
            append("\"flagBordersOnly\":${FlagWar.canOnlyAttackBorders},")
            append("\"flagDestruction\":${FlagWar.destructionEnabled},")
            append("\"occupied\":{")
            append(
                occupiedByTown.entries.joinToString(",") { (townId, coordinates) ->
                    "${JsonPrimitive(townId)}:${coordinates.joinToString(",", "[", "]")}"
                },
            )
            append("},\"colonized\":$colonized,")
            append("\"territoryOccupations\":{$territoryOccupations},")
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
}
