package net.aechronis.nodes.war

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object Warzone {
    data class NationScore(
        val nation: Nation,
        val millis: Long,
    )

    private class State(
        val territoryId: TerritoryId,
        val scores: MutableMap<UUID, Long> = linkedMapOf(),
        var activeNationId: UUID? = null,
        var activeSinceMillis: Long? = null,
        var stopped: Boolean = false,
    ) {
        var bossBar: BossBar? = null
    }

    private val states = hashMapOf<TerritoryId, State>()
    private val visibleBars = hashMapOf<UUID, BossBar>()
    private var ticker: Task? = null

    fun isActive(territory: Territory): Boolean = synchronized(this) {
        states[territory.id]?.stopped == false
    }

    /**
     * A registered warzone remains protected from permanent annexation after
     * scoring has been stopped. This is deliberately distinct from [isActive].
     */
    fun isRegistered(territory: Territory): Boolean = synchronized(this) {
        states.containsKey(territory.id)
    }

    /** A town with a registered warzone cannot be removed into wilderness. */
    fun ownsRegisteredZone(town: Town): Boolean = synchronized(this) {
        states.keys.any { territoryId -> Territory.fromId(territoryId)?.town === town }
    }

    /** Warzones are weekday activities, not global wars. */
    fun hasActiveZones(): Boolean = synchronized(this) {
        states.values.any { !it.stopped }
    }

    fun multiplierFor(territory: Territory): Double = if (isActive(territory)) Nodes.config.warzoneRateMultiplier else 1.0

    /**
     * Only claimed territory can host a warzone. A warzone capture needs an
     * owning town to occupy; wilderness territories are therefore ignored.
     */
    fun register(territories: Collection<Territory>) = synchronized(this) {
        val claimed = territories.filter { it.town != null }
        if (claimed.isEmpty()) return@synchronized
        claimed.forEach { territory ->
            val existing = states[territory.id]
            if (existing == null || existing.stopped) states[territory.id] = State(territory.id)
        }
        saveLocked()
        ensureTickerLocked()
        refreshBossBarsLocked()
    }

    /** Begin or hand off scoring after a town occupies the entire territory. */
    fun onTerritoryOccupied(
        territory: Territory,
        town: Town,
        nowMillis: Long = System.currentTimeMillis(),
    ) = synchronized(this) {
        val nation = town.nation ?: return@synchronized
        val state = states[territory.id] ?: return@synchronized
        if (state.stopped) return@synchronized
        accrueLocked(state, nowMillis)
        if (state.activeNationId != nation.uuid) {
            state.activeNationId = nation.uuid
            state.activeSinceMillis = nowMillis
        }
        saveLocked()
        refreshBossBarsLocked(nowMillis)
    }

    fun ranking(
        territory: Territory,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<NationScore> = synchronized(this) {
        val state = states[territory.id] ?: return@synchronized emptyList()
        scoreSnapshotLocked(state, nowMillis)
            .mapNotNull { (nationId, millis) -> Nation.fromUuid(nationId)?.let { NationScore(it, millis) } }
            .sortedWith(compareByDescending<NationScore> { it.millis }.thenBy { it.nation.name })
    }

    fun stop(
        territory: Territory,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<Nation> {
        val result = synchronized(this) {
            val state = states[territory.id]
                ?: return@synchronized Result.failure(IllegalArgumentException("Territory ${territory.id} is not a warzone"))
            if (state.stopped) {
                return@synchronized Result.failure(IllegalStateException("Territory ${territory.id} warzone is already stopped"))
            }

            accrueLocked(state, nowMillis)
            val scores = scoreSnapshotLocked(state, nowMillis)
                .mapNotNull { (nationId, millis) -> Nation.fromUuid(nationId)?.let { NationScore(it, millis) } }
                .sortedWith(compareByDescending<NationScore> { it.millis }.thenBy { it.nation.name })
            val winner = scores.firstOrNull()
                ?: return@synchronized Result.failure(IllegalStateException("Territory ${territory.id} has no warzone score"))
            if (scores.getOrNull(1)?.millis == winner.millis) {
                return@synchronized Result.failure(IllegalStateException("Territory ${territory.id} warzone is tied"))
            }

            state.activeNationId = null
            state.activeSinceMillis = null
            state.stopped = true
            saveLocked()
            refreshBossBarsLocked(nowMillis)
            Result.success(winner.nation)
        }
        if (result.isSuccess) FlagWar.cancelWarzoneAttacks(territory)
        return result
    }

    fun onPlayerTerritoryChanged(player: Player, territory: Territory?) = synchronized(this) {
        showForPlayerLocked(player, territory)
    }

    fun onPlayerQuit(player: Player) = synchronized(this) {
        visibleBars.remove(player.uuid)?.let(player::hideBossBar)
    }

    fun load() = synchronized(this) {
        clearRuntimeLocked()
        states.clear()
        if (!Files.exists(Nodes.config.pathWarzone)) return@synchronized
        runCatching {
            Files.newBufferedReader(Nodes.config.pathWarzone).use { reader ->
                JsonParser.parseReader(reader).asJsonObject
            }
        }.onSuccess { root ->
            root.get("zones")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (idText, value) ->
                runCatching {
                    val zone = value.asJsonObject
                    val state = State(TerritoryId(idText.toInt()))
                    state.stopped = zone.get("stopped")?.asBoolean ?: false
                    state.activeNationId = zone.get("active")?.takeUnless { it.isJsonNull }?.asString?.let(UUID::fromString)
                    state.activeSinceMillis = zone.get("activeSince")?.takeUnless { it.isJsonNull }?.asLong
                    zone.get("scores")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (nationId, score) ->
                        state.scores[UUID.fromString(nationId)] = score.asLong.coerceAtLeast(0L)
                    }
                    // Do not activate malformed legacy entries for wilderness.
                    // The source file is left alone; registering a warzone now
                    // always requires a territory to belong to a town.
                    if (Territory.fromId(state.territoryId)?.town != null) states[state.territoryId] = state
                }.onFailure { error ->
                    System.err.println("[Nodes] Ignoring invalid warzone $idText: ${error.message}")
                }
            }
        }.onFailure { error ->
            System.err.println("[Nodes] Failed to load warzones: ${error.message}")
        }
        ensureTickerLocked()
    }

    fun resetForReload() = synchronized(this) {
        clearRuntimeLocked()
        states.clear()
    }

    fun cleanup() = synchronized(this) {
        saveLocked()
        clearRuntimeLocked()
    }

    private fun clearRuntimeLocked() {
        ticker?.cancel()
        ticker = null
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            visibleBars.remove(player.uuid)?.let(player::hideBossBar)
        }
        visibleBars.clear()
        states.values.forEach { it.bossBar = null }
    }

    private fun ensureTickerLocked() {
        if (ticker != null || states.values.none { !it.stopped }) return
        ticker = MinecraftServer.getSchedulerManager()
            .buildTask {
                synchronized(this) {
                    refreshBossBarsLocked()
                    if (states.values.none { !it.stopped }) {
                        ticker?.cancel()
                        ticker = null
                    }
                }
            }
            .delay(TaskSchedule.tick(20))
            .repeat(TaskSchedule.tick(20))
            .schedule()
    }

    private fun accrueLocked(state: State, nowMillis: Long) {
        val nationId = state.activeNationId ?: return
        val since = state.activeSinceMillis ?: return
        val elapsed = (nowMillis - since).coerceAtLeast(0L)
        if (elapsed == 0L) return
        val existing = state.scores[nationId] ?: 0L
        val cap = Nodes.config.warzoneScoreCapMillis
        state.scores[nationId] = if (cap == null) existing + elapsed else (existing + elapsed).coerceAtMost(cap)
        state.activeSinceMillis = nowMillis
    }

    private fun scoreSnapshotLocked(state: State, nowMillis: Long): Map<UUID, Long> {
        val result = LinkedHashMap(state.scores)
        val nationId = state.activeNationId
        val since = state.activeSinceMillis
        if (nationId != null && since != null) {
            val elapsed = (nowMillis - since).coerceAtLeast(0L)
            val existing = result[nationId] ?: 0L
            val cap = Nodes.config.warzoneScoreCapMillis
            result[nationId] = if (cap == null) existing + elapsed else (existing + elapsed).coerceAtMost(cap)
        }
        return result
    }

    private fun refreshBossBarsLocked(nowMillis: Long = System.currentTimeMillis()) {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            showForPlayerLocked(player, Territory.fromPlayer(player), nowMillis)
        }
    }

    private fun showForPlayerLocked(
        player: Player,
        territory: Territory?,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val state = territory?.let { states[it.id] }?.takeIf { !it.stopped }
        val desired = state?.let { bossBarLocked(it, nowMillis) }
        val previous = visibleBars[player.uuid]
        if (previous !== desired) {
            previous?.let(player::hideBossBar)
            if (desired != null) {
                player.showBossBar(desired)
                visibleBars[player.uuid] = desired
            } else {
                visibleBars.remove(player.uuid)
            }
        }
    }

    private fun bossBarLocked(state: State, nowMillis: Long): BossBar {
        val scores = scoreSnapshotLocked(state, nowMillis)
        val leader = scores
            .mapNotNull { (nationId, score) -> Nation.fromUuid(nationId)?.let { it to score } }
            .sortedWith(compareByDescending<Pair<Nation, Long>> { it.second }.thenBy { it.first.name })
            .firstOrNull()
        val title = if (leader == null) {
            "Warzone: no nation occupies the territory"
        } else {
            "Warzone: ${leader.first.name} — ${formatTime(leader.second)}"
        }
        val progress = Nodes.config.warzoneScoreCapMillis
            ?.takeIf { it > 0L }
            ?.let { cap -> ((leader?.second ?: 0L).toDouble() / cap).coerceIn(0.0, 1.0).toFloat() }
            ?: 1f
        return state.bossBar?.also {
            it.name(Component.text(title, NamedTextColor.GOLD))
            it.progress(progress)
        } ?: BossBar.bossBar(
            Component.text(title, NamedTextColor.GOLD),
            progress,
            BossBar.Color.YELLOW,
            BossBar.Overlay.PROGRESS,
        ).also { state.bossBar = it }
    }

    private fun saveLocked() {
        if (!Nodes.config.save) return
        val zones = states.values.sortedBy { it.territoryId.toInt() }.joinToString(",") { state ->
            val active = state.activeNationId?.let { JsonPrimitive(it.toString()).toString() } ?: "null"
            val activeSince = state.activeSinceMillis?.toString() ?: "null"
            val scores = state.scores.entries.sortedBy { it.key.toString() }.joinToString(",") { (nationId, score) ->
                "${JsonPrimitive(nationId.toString())}:$score"
            }
            "${JsonPrimitive(state.territoryId.toString())}:{\"active\":$active,\"activeSince\":$activeSince,\"stopped\":${state.stopped},\"scores\":{$scores}}"
        }
        val json = "{\"zones\":{$zones}}"
        val path = Nodes.config.pathWarzone.toAbsolutePath()
        val parent = path.parent ?: return
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun formatTime(millis: Long): String {
        val seconds = millis / 1000L
        return "%02d:%02d:%02d".format(seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
    }
}
