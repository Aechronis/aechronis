package net.aechronis.stats

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minestom.server.entity.Player
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    /** A player's nation when last credited; null for nations and nationless players. */
    var nation: String? = null,
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

/** Players online per nation at one moment, [time] in epoch seconds. */
@Serializable
data class RallySample(
    val time: Long,
    val nations: Map<String, Int>,
)

@Serializable
private data class SavedBucket(
    val players: Map<String, PlayerStats> = emptyMap(),
    val nations: Map<String, PlayerStats> = emptyMap(),
    val rally: List<RallySample> = emptyList(),
)

@Serializable
private data class SavedStats(
    val players: Map<String, PlayerStats>,
    val nations: Map<String, PlayerStats>,
    val war: Map<String, SavedBucket> = emptyMap(),
    val currentWar: String? = null,
)

/** Leaderboard categories accepted by the HTTP API. */
val SORT_KEYS = listOf("kills", "deaths", "kd", "capsBroken", "capsCompleted", "playtime")
val NATION_SORT_KEYS = listOf("kills", "deaths", "kd", "playtime")

private class Bucket {
    val players = HashMap<UUID, PlayerStats>()
    val nations = HashMap<String, PlayerStats>()
    val rally = ArrayList<RallySample>()
}

/** War snapshots leave out playtime; they track the rally graph instead. */
private val WAR_SORT_KEYS = SORT_KEYS - "playtime"
private val WAR_NATION_SORT_KEYS = NATION_SORT_KEYS - "playtime"

/** Rally graph resolution. */
private const val RALLY_SECONDS = 600L

/** War stats are keyed by the Eastern date the war started on. */
private val WAR_ZONE = ZoneId.of("America/New_York")

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
    var warActive: () -> Boolean = { false }

    /** Key of the war in progress; saved so a restart mid-war resumes the same bucket. */
    private var currentWar: String? = null

    /** The war in progress gets its start date as key; a second war on the same date becomes "yyyy-MM-dd~2", "~3", ... */
    private fun warWeek(now: ZonedDateTime): String? {
        if (!warActive()) {
            if (currentWar != null) dirty = true
            return null.also { currentWar = null }
        }
        currentWar?.let { return it }
        val date = now.withZoneSameInstant(WAR_ZONE).toLocalDate().toString()
        var key = date
        var n = 2
        while (war.containsKey(key)) key = "$date~${n++}"
        dirty = true
        return key.also { currentWar = it }
    }

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
        warToo: Boolean = true,
        change: (PlayerStats) -> Unit,
    ) = synchronized(this) {
        val nation = nationOf(uuid)
        val week = if (warToo) warWeek(ZonedDateTime.now()) else null
        val buckets = listOfNotNull(main, week?.let { war.getOrPut(it) { Bucket() } })
        buckets.forEach { bucket ->
            val entry = bucket.players.getOrPut(uuid) { PlayerStats() }
            if (name != null) entry.name = name
            entry.nation = nation
            change(entry)
            nation?.let { change(bucket.nations.getOrPut(it) { PlayerStats(name = it) }) }
        }
        dirty = true
    }

    /** Credits [seconds] of online time to each player in [online]. */
    internal fun addPlaytime(
        online: Collection<Player>,
        seconds: Long,
    ) {
        online.forEach { player -> credit(player.uuid, player.username, warToo = false) { it.playtime += seconds } }
        sampleRally(online)
    }

    /** While a war is running, records players online per nation once per 10 minute slot. */
    private fun sampleRally(online: Collection<Player>) =
        synchronized(this) {
            val now = ZonedDateTime.now()
            val week = warWeek(now) ?: return
            val slot = now.toEpochSecond() / RALLY_SECONDS
            val bucket = war.getOrPut(week) { Bucket() }
            if (bucket.rally.lastOrNull()?.let { it.time / RALLY_SECONDS == slot } == true) return
            val counts = online.mapNotNull { nationOf(it.uuid) }.groupingBy { it }.eachCount()
            // Stamped at the slot start so every sample sits exactly on a 10 minute mark (the first sample lands within 30s of the war starting).
            bucket.rally.add(RallySample(slot * RALLY_SECONDS, counts))
            dirty = true
        }

    internal fun load() =
        synchronized(this) {
            main.players.clear()
            main.nations.clear()
            war.clear()
            currentWar = null
            if (Files.exists(path)) {
                val loaded = json.decodeFromString<SavedStats>(Files.readString(path))
                loaded.players.forEach { (id, stats) -> main.players[UUID.fromString(id)] = stats }
                main.nations.putAll(loaded.nations)
                loaded.war.forEach { (week, saved) ->
                    val bucket = war.getOrPut(week) { Bucket() }
                    saved.players.forEach { (id, stats) -> bucket.players[UUID.fromString(id)] = stats }
                    bucket.nations.putAll(saved.nations)
                    bucket.rally.addAll(saved.rally)
                }
                currentWar = loaded.currentWar
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
                        war.mapValues { (_, b) -> SavedBucket(b.players.mapKeys { it.key.toString() }, b.nations, b.rally) },
                        currentWar,
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
        val keys =
            when {
                warBoard && nationBoard -> WAR_NATION_SORT_KEYS
                warBoard -> WAR_SORT_KEYS
                nationBoard -> NATION_SORT_KEYS
                else -> SORT_KEYS
            }
        require(sort in keys) { "Unknown sort '$sort'" }
        var weeks = emptyList<String>()
        var chosenWeek: String? = null
        var rally = emptyList<RallySample>()
        val snapshot =
            synchronized(this) {
                val bucket =
                    if (warBoard) {
                        weeks = war.keys.reversed()
                        chosenWeek = week ?: weeks.firstOrNull()
                        chosenWeek?.let { war[it] }?.also { rally = it.rally.toList() }
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
                if (!nationBoard) append(",\"nation\":${Json.encodeToString(p.nation)}")
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
                ",\"week\":${chosenWeek?.let { "\"$it\"" } ?: "null"},\"weeks\":[${weeks.joinToString(",") { "\"$it\"" }}]," +
                    "\"rally\":${Json.encodeToString(rally)}"
            } else {
                ""
            }
        return "{\"sort\":\"$sort\",\"total\":${snapshot.size}$warJson,\"players\":[${rows.joinToString(
            ",",
        )}],\"player\":${meJson ?: "null"}}"
    }

    /** Per war week (newest first, at most [limit]) the named player's or nation's stats; weeks they didn't play are left out. */
    internal fun history(
        name: String,
        nation: Boolean,
        limit: Int,
    ): String {
        val rows =
            synchronized(this) {
                war.entries.reversed().mapNotNull { (week, bucket) ->
                    val stats =
                        (if (nation) bucket.nations.values else bucket.players.values).firstOrNull {
                            it.name.equals(
                                name,
                                ignoreCase = true,
                            )
                        }
                    stats?.let { week to it.copy() }
                }
            }.take(limit)
        val weeks =
            rows.joinToString(",") { (week, p) ->
                "{\"week\":\"$week\",\"kills\":${p.kills},\"deaths\":${p.deaths},\"kd\":${"%.2f".format(java.util.Locale.ROOT, p.kd)}," +
                    "\"capsBroken\":${p.capsBroken},\"capsCompleted\":${p.capsCompleted}}"
            }
        val shown = Json.encodeToString(rows.firstOrNull()?.second?.name ?: name)
        return "{\"name\":$shown,\"type\":\"${if (nation) "nation" else "player"}\",\"weeks\":[$weeks]}"
    }

    /** Up to [limit] player and nation names matching [q]: prefix matches first, then other substring matches, each alphabetical. */
    internal fun search(
        q: String,
        limit: Int,
    ): String {
        val needle = q.lowercase()
        val names =
            synchronized(this) {
                main.players.values
                    .filter { it.name.isNotEmpty() }
                    .map { Triple(it.name, "player", it.nation) } +
                    main.nations.values
                        .filter { it.name.isNotEmpty() }
                        .map { Triple(it.name, "nation", null) }
            }
        val hits =
            names
                .filter { needle in it.first.lowercase() }
                .sortedWith(
                    compareBy<Triple<String, String, String?>> { !it.first.startsWith(needle, ignoreCase = true) }
                        .thenBy { it.first.lowercase() },
                ).take(limit)
        val results =
            hits.joinToString(",") { (n, type, nation) ->
                val nationJson = nation?.let { ",\"nation\":${Json.encodeToString(it)}" } ?: ""
                "{\"name\":${Json.encodeToString(n)},\"type\":\"$type\"$nationJson}"
            }
        return "{\"results\":[$results]}"
    }
}
