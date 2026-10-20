package net.aechronis.stats

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minestom.server.entity.Player
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
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
private data class SavedBucket(
    val players: Map<String, PlayerStats> = emptyMap(),
    val nations: Map<String, PlayerStats> = emptyMap(),
)

@Serializable
private data class SavedStats(
    val players: Map<String, PlayerStats>,
    val nations: Map<String, PlayerStats>,
    val war: Map<String, SavedBucket> = emptyMap(),
)

/** Leaderboard categories accepted by the HTTP API. */
val SORT_KEYS = listOf("kills", "deaths", "kd", "capsBroken", "capsCompleted", "playtime")
val NATION_SORT_KEYS = listOf("kills", "deaths", "kd", "playtime")

private class Bucket {
    val players = HashMap<UUID, PlayerStats>()
    val nations = HashMap<String, PlayerStats>()
}

/** Weekly war stats are recorded only Saturdays from 1pm to 4pm Eastern (daylight saving is handled by the zone). */
private val WAR_ZONE = ZoneId.of("America/New_York")

/** The date of the war Saturday containing [now], or null outside the 1pm-4pm Eastern window. */
internal fun warWeekOf(now: ZonedDateTime): String? {
    val local = now.withZoneSameInstant(WAR_ZONE)
    return if (local.dayOfWeek == DayOfWeek.SATURDAY && local.hour in 13..15) local.toLocalDate().toString() else null
}

object Stats {
    private val path = Path.of("stats", "stats.json")
    private val main = Bucket()

    /** One bucket per war Saturday, keyed by its date (yyyy-MM-dd). */
    private val war = java.util.TreeMap<String, Bucket>()

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
    ) = credit(uuid, name) { apply(it, stat) }

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

    /** Applies [change] to the player and their nation in the all-time stats and, during the war window, this week's war stats. */
    private fun credit(
        uuid: UUID,
        name: String?,
        change: (PlayerStats) -> Unit,
    ) = synchronized(this) {
        val nation = nationOf(uuid)
        val buckets = listOfNotNull(main, warWeekOf(ZonedDateTime.now())?.let { war.getOrPut(it) { Bucket() } })
        buckets.forEach { bucket ->
            val entry = bucket.players.getOrPut(uuid) { PlayerStats() }
            if (name != null) entry.name = name
            change(entry)
            nation?.let { change(bucket.nations.getOrPut(it) { PlayerStats(name = it) }) }
        }
        dirty = true
    }

    /** Credits [seconds] of online time to each player in [online]. */
    internal fun addPlaytime(
        online: Collection<Player>,
        seconds: Long,
    ) = online.forEach { player -> credit(player.uuid, player.username) { it.playtime += seconds } }

    internal fun load() =
        synchronized(this) {
            main.players.clear()
            main.nations.clear()
            war.clear()
            if (Files.exists(path)) {
                val loaded = json.decodeFromString<SavedStats>(Files.readString(path))
                loaded.players.forEach { (id, stats) -> main.players[UUID.fromString(id)] = stats }
                main.nations.putAll(loaded.nations)
                loaded.war.forEach { (week, saved) ->
                    val bucket = war.getOrPut(week) { Bucket() }
                    saved.players.forEach { (id, stats) -> bucket.players[UUID.fromString(id)] = stats }
                    bucket.nations.putAll(saved.nations)
                }
            }
            dirty = false
        }

    internal fun save() {
        val json =
            synchronized(this) {
                if (!dirty) return
                dirty = false
                Json.encodeToString(
                    SavedStats(
                        main.players.mapKeys { it.key.toString() },
                        main.nations,
                        war.mapValues { (_, b) -> SavedBucket(b.players.mapKeys { it.key.toString() }, b.nations) },
                    ),
                )
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

    /**
     * Top [limit] players by [sort] plus, optionally, one player's stats with their rank in every category.
     * [warBoard] switches to weekly war stats for [week] (a yyyy-MM-dd date, or null for the latest week).
     */
    internal fun query(
        sort: String,
        limit: Int,
        playerName: String?,
        nationBoard: Boolean = false,
        warBoard: Boolean = false,
        week: String? = null,
    ): String {
        val keys = if (nationBoard) NATION_SORT_KEYS else SORT_KEYS
        require(sort in keys) { "Unknown sort '$sort'" }
        var weeks = emptyList<String>()
        var chosenWeek: String? = null
        val snapshot =
            synchronized(this) {
                val bucket =
                    if (warBoard) {
                        weeks = war.keys.reversed()
                        chosenWeek = week ?: weeks.firstOrNull()
                        chosenWeek?.let { war[it] }
                    } else {
                        main
                    }
                (if (nationBoard) bucket?.nations else bucket?.players)
                    ?.values
                    ?.filter { it.name.isNotEmpty() }
                    ?.map { it.copy() }
                    .orEmpty()
            }
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
        val warJson =
            if (warBoard) {
                ",\"week\":${chosenWeek?.let { "\"$it\"" } ?: "null"},\"weeks\":[${weeks.joinToString(",") { "\"$it\"" }}]"
            } else {
                ""
            }
        return "{\"sort\":\"$sort\",\"total\":${snapshot.size}$warJson,\"players\":[${rows.joinToString(
            ",",
        )}],\"player\":${meJson ?: "null"}}"
    }
}
