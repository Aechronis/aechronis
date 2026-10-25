package net.aechronis.nodes.objects

import kotlinx.serialization.json.Json
import net.aechronis.nodes.Nodes
import net.aechronis.server.io.AtomicFiles
import net.aechronis.vanilla.managers.Ores
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Mining outposts: unclaimable territories where blocks cannot be broken or placed
 * and every ore is a per-player regenerating ore (see [Ores]).
 */
object Outposts {
    private val ids: MutableSet<TerritoryId> = ConcurrentHashMap.newKeySet()

    fun contains(territory: Territory?): Boolean = territory != null && territory.id in ids

    fun all(): List<TerritoryId> = ids.sortedBy { it.toInt() }

    fun add(territory: Territory): Result<Unit> {
        if (territory.town != null) return Result.failure(IllegalStateException("Territory ${territory.id} is claimed by a town"))
        if (!ids.add(territory.id)) return Result.failure(IllegalStateException("Territory ${territory.id} is already an outpost"))
        save()
        return Result.success(Unit)
    }

    fun remove(territory: Territory): Result<Unit> {
        if (!ids.remove(territory.id)) return Result.failure(IllegalStateException("Territory ${territory.id} is not an outpost"))
        save()
        return Result.success(Unit)
    }

    fun load() {
        ids.clear()
        val path = Nodes.config.pathOutposts
        if (Files.exists(path)) {
            Json.decodeFromString<List<Int>>(Files.readString(path)).mapNotNullTo(ids) { id ->
                TerritoryId(id).takeIf { Territory.fromId(it) != null }
            }
        }
        Ores.autoOreArea = { _, x, z -> contains(Territory.fromBlock(x, z)) }
    }

    fun resetForReload() {
        ids.clear()
        Ores.autoOreArea = null
    }

    fun cleanup() = resetForReload()

    private fun save() {
        if (!Nodes.config.save) return
        runCatching {
            AtomicFiles.writeString(Nodes.config.pathOutposts.toAbsolutePath(), Json.encodeToString(all().map { it.toInt() }))
        }.onFailure { error -> System.err.println("[Nodes] Failed to save outposts: ${error.message}") }
    }
}
