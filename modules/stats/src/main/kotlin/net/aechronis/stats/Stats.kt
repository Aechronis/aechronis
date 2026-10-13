package net.aechronis.stats

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minestom.server.entity.Player
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class Stat(
    val key: String,
) {
    KILLS("kills"),
    DEATHS("deaths"),
    CAPS_BROKEN("capsBroken"),
    CAPS_COMPLETED("capsCompleted"),
}

@Serializable
data class PlayerStats(
    var name: String = "",
    var kills: Int = 0,
    var deaths: Int = 0,
    var capsBroken: Int = 0,
    var capsCompleted: Int = 0,
    var playtime: Long = 0,
) {
    val kd: Double get() = if (deaths == 0) kills.toDouble() else kills.toDouble() / deaths

    fun value(key: String): Double =
        when (key) {
            "kills" -> kills.toDouble()
            "deaths" -> deaths.toDouble()
            "kd" -> kd
            "capsBroken" -> capsBroken.toDouble()
            "capsCompleted" -> capsCompleted.toDouble()
            "playtime" -> playtime.toDouble()
            else -> throw IllegalArgumentException(key)
        }
}

@Serializable
private data class SavedStats(
    val players: Map<String, PlayerStats>,
    val nations: Map<String, PlayerStats>,
)

/** Leaderboard categories accepted by the HTTP API. */
val SORT_KEYS = listOf("kills", "deaths", "kd", "capsBroken", "capsCompleted", "playtime")
val NATION_SORT_KEYS = listOf("kills", "deaths", "kd", "playtime")

object Stats {
    private val path = Path.of("stats", "stats.json")
    private val players = HashMap<UUID, PlayerStats>()
    private val nations = HashMap<String, PlayerStats>()

    /** Set by the nodes module: a player's current nation name, or null. Kills, deaths and playtime count for it. */
    @Volatile
    var nationOf: (UUID) -> String? = { null }

    /** Set by the nodes module: true when two players are in the same town/nation or allied. Such kills don't count. */
    @Volatile
    var areFriendly: (Player, Player) -> Boolean = { _, _ -> false }
    private var dirty = false
    private val json = Json { ignoreUnknownKeys = true }

    fun add(
        player: Player,
        stat: Stat,
    ) = add(player.uuid, player.username, stat)

    /** [name] is null when the player may be offline; the last known name is kept. */
    fun add(
        uuid: UUID,
        name: String?,
        stat: Stat,
    ) = synchronized(this) {
        val entry = players.getOrPut(uuid) { PlayerStats() }
        if (name != null) entry.name = name
        apply(entry, stat)
        nationOf(uuid)?.let { apply(nations.getOrPut(it) { PlayerStats(name = it) }, stat) }
        dirty = true
    }

    private fun apply(
        entry: PlayerStats,
        stat: Stat,
    ) {
        when (stat) {
            Stat.KILLS -> entry.kills++
            Stat.DEATHS -> entry.deaths++
            Stat.CAPS_BROKEN -> entry.capsBroken++
            Stat.CAPS_COMPLETED -> entry.capsCompleted++
        }
    }

    /** Credits [seconds] of online time to each player in [online]. */
    internal fun addPlaytime(
        online: Collection<Player>,
        seconds: Long,
    ) = synchronized(this) {
        online.forEach { player ->
            val entry = players.getOrPut(player.uuid) { PlayerStats() }
            entry.name = player.username
            entry.playtime += seconds
            nationOf(player.uuid)?.let { nations.getOrPut(it) { PlayerStats(name = it) }.playtime += seconds }
        }
        if (online.isNotEmpty()) dirty = true
    }

    internal fun load() =
        synchronized(this) {
            players.clear()
            nations.clear()
            if (Files.exists(path)) {
                val loaded = json.decodeFromString<SavedStats>(Files.readString(path))
                loaded.players.forEach { (id, stats) -> players[UUID.fromString(id)] = stats }
                nations.putAll(loaded.nations)
            }
            dirty = false
        }

    internal fun save() {
        val json =
            synchronized(this) {
                if (!dirty) return
                dirty = false
                Json.encodeToString(SavedStats(players.mapKeys { it.key.toString() }, nations))
            }
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("stats.json.tmp")
            Files.writeString(tmp, json)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            synchronized(this) { dirty = true }
            println("[Stats] Save failed: $error")
        }
    }

    /** Top [limit] players by [sort] plus, optionally, one player's stats with their rank in every category. */
    internal fun query(
        sort: String,
        limit: Int,
        playerName: String?,
        nationBoard: Boolean = false,
    ): String {
        val keys = if (nationBoard) NATION_SORT_KEYS else SORT_KEYS
        require(sort in keys) { "Unknown sort '$sort'" }
        val snapshot =
            synchronized(this) { (if (nationBoard) nations else players).values.filter { it.name.isNotEmpty() }.map { it.copy() } }
        val top = snapshot.sortedWith(compareByDescending<PlayerStats> { it.value(sort) }.thenBy { it.name.lowercase() })

        fun row(p: PlayerStats) =
            buildString {
                append("{\"name\":${Json.encodeToString(p.name)}")
                keys.forEach {
                    append(
                        ",\"$it\":${p.value(it).let { v ->
                            if (it == "kd") "%.2f".format(java.util.Locale.ROOT, v) else v.toLong().toString()
                        }}",
                    )
                }
            }
        // Ties share a rank, matching the per-player rank below.
        var rank = 0
        val rows =
            top.take(limit).mapIndexed { i, p ->
                if (i == 0 || p.value(sort) != top[i - 1].value(sort)) rank = i + 1
                row(p) + ",\"rank\":$rank}"
            }
        val me = playerName?.let { n -> snapshot.firstOrNull { it.name.equals(n, ignoreCase = true) } }
        val meJson =
            me?.let { p ->
                val ranks =
                    keys.joinToString(",") { key ->
                        val v = p.value(key)
                        "\"$key\":${1 + snapshot.count { it.value(key) > v }}"
                    }
                row(p) + ",\"ranks\":{$ranks}}"
            }
        return "{\"sort\":\"$sort\",\"total\":${snapshot.size},\"players\":[${rows.joinToString(",")}],\"player\":${meJson ?: "null"}}"
    }
}
