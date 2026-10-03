package net.aechronis.nodes.war

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.server.modules.ModuleScheduler
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

/**
 * Scheduled king-of-the-hill events on any territory, claimed or not.
 *
 * Between a zone's start and end time, the nation holding the territory
 * (its occupier's nation, otherwise its owner's nation) accrues hold time.
 * Unclaimed territory has no holder until a nation captures its core.
 * When the zone ends, the nation with the most hold time wins the territory.
 * Hold time only accrues while the server is running.
 */
object Warzone {
    data class NationScore(
        val nation: Nation,
        val millis: Long,
    )

    data class Summary(
        val territoryId: TerritoryId,
        val startMillis: Long,
        val endMillis: Long?,
        val started: Boolean,
        val leader: NationScore?,
    )

    private class State(
        val territoryId: TerritoryId,
        val startMillis: Long,
        // null only for zones migrated from the old open-ended format; those end on an admin stop
        val endMillis: Long?,
        var started: Boolean = false,
        val scores: MutableMap<UUID, Long> = linkedMapOf(),
        // last time each nation held the zone, used to break ties
        val lastHeld: MutableMap<UUID, Long> = linkedMapOf(),
    ) {
        var lastTickMillis: Long? = null
        var bossBar: BossBar? = null
    }

    private sealed interface Outcome {
        val territory: Territory

        class Won(override val territory: Territory, val winner: Nation, val millis: Long) : Outcome
        class NoWinner(override val territory: Territory) : Outcome
    }

    private const val SAVE_INTERVAL_MILLIS = 30_000L

    private val states = hashMapOf<TerritoryId, State>()
    private val visibleBars = hashMapOf<UUID, BossBar>()
    private var ticker: Task? = null
    private var lastSaveMillis = 0L

    /** True while the zone's scheduled window is running and flags may be placed in it. */
    fun isActive(territory: Territory): Boolean = synchronized(this) {
        states[territory.id]?.started == true
    }

    /**
     * A scheduled or running warzone protects its territory from being
     * claimed or unclaimed and its town from deletion and home annexation.
     * Finished zones are removed, so the protection ends with the zone.
     */
    fun isRegistered(territory: Territory): Boolean = synchronized(this) {
        states.containsKey(territory.id)
    }

    /** A town with a scheduled or running warzone cannot be removed into wilderness. */
    fun ownsRegisteredZone(town: Town): Boolean = synchronized(this) {
        states.keys.any { territoryId -> Territory.fromId(territoryId)?.town === town }
    }

    /** Warzones are weekday activities, not global wars. */
    fun hasActiveZones(): Boolean = synchronized(this) {
        states.values.any { it.started }
    }

    fun multiplierFor(territory: Territory): Double = if (isActive(territory)) Nodes.config.warzoneRateMultiplier else 1.0

    /**
     * Schedule warzones on [territories] from [startMillis] to [endMillis].
     * Fails without changing anything if a territory already has a
     * scheduled or running zone.
     */
    fun schedule(
        territories: Collection<Territory>,
        startMillis: Long,
        endMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<Unit> = synchronized(this) {
        if (territories.isEmpty()) return@synchronized Result.failure(IllegalArgumentException("No territories given"))
        if (endMillis <= startMillis) return@synchronized Result.failure(IllegalArgumentException("Warzone must end after it starts"))
        if (endMillis <= nowMillis) return@synchronized Result.failure(IllegalArgumentException("Warzone end time is in the past"))
        val existing = territories.filter { states.containsKey(it.id) }
        if (existing.isNotEmpty()) {
            return@synchronized Result.failure(
                IllegalStateException("Already a warzone (cancel it first): ${existing.joinToString(", ") { it.id.toString() }}"),
            )
        }
        territories.forEach { territory ->
            states[territory.id] = State(territory.id, startMillis, endMillis)
        }
        saveLocked()
        ensureTickerLocked()
        Result.success(Unit)
    }

    fun ranking(
        territory: Territory,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<NationScore> = synchronized(this) {
        val state = states[territory.id] ?: return@synchronized emptyList()
        accrueLocked(state, nowMillis)
        rankingLocked(state)
    }

    fun summary(territory: Territory): Summary? = synchronized(this) {
        states[territory.id]?.let(::summaryLocked)
    }

    fun summaries(): List<Summary> = synchronized(this) {
        states.values.sortedWith(compareBy<State> { it.startMillis }.thenBy { it.territoryId.toInt() }).map(::summaryLocked)
    }

    /** End a running warzone now and award it, as if its time had run out. */
    fun stop(
        territory: Territory,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<Unit> {
        val outcome = synchronized(this) {
            val state = states[territory.id]
                ?: return Result.failure(IllegalArgumentException("Territory ${territory.id} is not a warzone"))
            if (!state.started) {
                return Result.failure(IllegalStateException("Territory ${territory.id} warzone has not started; use cancel instead"))
            }
            finishLocked(state, nowMillis)
        }
        outcome?.let(::award)
        return Result.success(Unit)
    }

    /** Remove a scheduled or running warzone without awarding it. */
    fun cancel(territory: Territory): Result<Unit> {
        synchronized(this) {
            val state = states.remove(territory.id)
                ?: return Result.failure(IllegalArgumentException("Territory ${territory.id} is not a warzone"))
            state.bossBar?.let(::hideBarLocked)
            saveLocked()
        }
        FlagWar.cancelWarzoneAttacks(territory)
        // Unclaimed land cannot stay occupied once its warzone is gone.
        if (territory.town == null) Town.release(territory)
        return Result.success(Unit)
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
        if (Files.notExists(Nodes.config.pathWarzone)) return@synchronized
        val nowMillis = System.currentTimeMillis()
        try {
            val root = Files.newBufferedReader(Nodes.config.pathWarzone).use { reader ->
                Json.parseToJsonElement(reader.readText()).jsonObject
            }
            val zones = requireNotNull(root["zones"]) { "Missing 'zones' object" }.jsonObject
            zones.entries.forEach { (idText, value) ->
                try {
                    val state = parseZone(TerritoryId(idText.toInt()), value.jsonObject, nowMillis) ?: return@forEach
                    if (Territory.fromId(state.territoryId) != null) states[state.territoryId] = state
                } catch (error: Exception) {
                    throw IllegalArgumentException("Invalid warzone '$idText'", error)
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Failed to load warzones from ${Nodes.config.pathWarzone}", error)
        }
        ensureTickerLocked()
    }

    fun resetForReload() = synchronized(this) {
        clearRuntimeLocked()
        states.clear()
    }

    fun cleanup(persistState: Boolean = true) = synchronized(this) {
        if (persistState) {
            val nowMillis = System.currentTimeMillis()
            states.values.forEach { accrueLocked(it, nowMillis) }
            saveLocked()
        }
        clearRuntimeLocked()
    }

    private fun parseZone(territoryId: TerritoryId, zone: JsonObject, nowMillis: Long): State? {
        val start = zone["start"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
        val state = if (start != null) {
            State(
                territoryId,
                startMillis = start,
                endMillis = zone["end"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
                started = zone["started"]?.jsonPrimitive?.boolean ?: false,
            )
        } else {
            // Old format: manually started zones with no schedule. Stopped
            // ones are finished, and running ones continue until an admin stops them.
            if (zone["stopped"]?.jsonPrimitive?.boolean == true) return null
            System.err.println("[Nodes] Warzone $territoryId has no end time; it runs until /nda warzone stop")
            State(territoryId, startMillis = nowMillis, endMillis = null, started = true)
        }
        zone["scores"]?.jsonObject?.entries?.forEach { (nationId, score) ->
            state.scores[UUID.fromString(nationId)] = score.jsonPrimitive.long.coerceAtLeast(0L)
        }
        zone["lastHeld"]?.jsonObject?.entries?.forEach { (nationId, time) ->
            state.lastHeld[UUID.fromString(nationId)] = time.jsonPrimitive.long
        }
        return state
    }

    private fun clearRuntimeLocked() {
        ticker?.cancel()
        ticker = null
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            visibleBars.remove(player.uuid)?.let(player::hideBossBar)
        }
        visibleBars.clear()
        states.values.forEach {
            it.bossBar = null
            it.lastTickMillis = null
        }
    }

    private fun ensureTickerLocked() {
        if (ticker != null || states.isEmpty()) return
        ticker = ModuleScheduler
            .buildTask { tick() }
            .delay(TaskSchedule.tick(20))
            .repeat(TaskSchedule.tick(20))
            .schedule()
    }

    private fun tick() {
        val nowMillis = System.currentTimeMillis()
        val started = mutableListOf<State>()
        val outcomes = synchronized(this) {
            val outcomes = mutableListOf<Outcome>()
            states.values.toList().forEach { state ->
                if (!state.started && nowMillis >= state.startMillis) {
                    state.started = true
                    state.lastTickMillis = maxOf(state.startMillis, nowMillis - 1_000L)
                    started += state
                }
                accrueLocked(state, nowMillis)
                val end = state.endMillis
                if (state.started && end != null && nowMillis >= end) {
                    finishLocked(state, nowMillis)?.let(outcomes::add)
                }
            }
            if (started.isNotEmpty() || nowMillis - lastSaveMillis >= SAVE_INTERVAL_MILLIS) saveLocked()
            refreshBossBarsLocked()
            if (states.isEmpty()) {
                ticker?.cancel()
                ticker = null
            }
            outcomes
        }
        // A zone whose whole window passed while the server was down ends without an announcement.
        started.filter { state -> state.endMillis.let { it == null || it > nowMillis } }.forEach { state ->
            val end = state.endMillis?.let { " for ${formatDuration(it - nowMillis)}" } ?: ""
            Message.broadcast("${ChatColor.DARK_RED}[Warzone] Territory ${state.territoryId} is now a warzone$end")
        }
        outcomes.forEach { outcome ->
            runCatching { award(outcome) }.onFailure { error ->
                System.err.println("[Nodes] Failed to award warzone ${outcome.territory.id}: ${error.message}")
                error.printStackTrace()
            }
        }
    }

    /** Remove [state] and return its result. Caller must award it outside the lock. */
    private fun finishLocked(state: State, nowMillis: Long): Outcome? {
        accrueLocked(state, nowMillis)
        states.remove(state.territoryId)
        state.bossBar?.let(::hideBarLocked)
        saveLocked()
        val territory = Territory.fromId(state.territoryId) ?: return null
        val winner = rankingLocked(state).firstOrNull() ?: return Outcome.NoWinner(territory)
        return Outcome.Won(territory, winner.nation, winner.millis)
    }

    /**
     * Must run without holding this object's lock: Town.annexTerritory takes the
     * occupation lock, which is acquired before this one elsewhere.
     */
    private fun award(outcome: Outcome) {
        val territory = outcome.territory
        FlagWar.cancelWarzoneAttacks(territory)
        when (outcome) {
            is Outcome.NoWinner -> Message.broadcast(
                "${ChatColor.DARK_RED}[Warzone] Territory ${territory.id} ended with no nation holding it",
            )

            is Outcome.Won -> {
                val winner = outcome.winner
                val owner = territory.town
                if (owner != null && owner.nation === winner) {
                    // The owning nation defended its land; clear any enemy
                    // occupation, including chunks taken without the core.
                    Town.release(territory)
                    Message.broadcast(
                        "${ChatColor.DARK_RED}[Warzone] ${winner.name} held territory ${territory.id} " +
                            "for ${formatDuration(outcome.millis)} and keeps it",
                    )
                } else {
                    Town.annexTerritory(winner.capital, territory)
                    Message.broadcast(
                        "${ChatColor.DARK_RED}[Warzone] ${winner.name} held territory ${territory.id} " +
                            "for ${formatDuration(outcome.millis)}; it has been annexed by ${winner.capital.name}",
                    )
                }
            }
        }
    }

    /** The nation currently holding a territory: its occupier's, otherwise its owner's. */
    private fun holderOf(territory: Territory): Nation? = (territory.occupier ?: territory.town)?.nation

    /**
     * Credit the current holder with the time since the last accrual,
     * limited to the zone's window. Time while the server was down is never
     * credited because [State.lastTickMillis] starts fresh on load.
     */
    private fun accrueLocked(state: State, nowMillis: Long) {
        if (!state.started) return
        val end = state.endMillis
        val until = if (end == null) nowMillis else minOf(nowMillis, end)
        val since = state.lastTickMillis
        state.lastTickMillis = until
        if (since == null) return
        val elapsed = (until - since).coerceAtLeast(0L)
        // No time passed (e.g. the whole window elapsed while the server was down): credit nobody.
        if (elapsed == 0L) return
        val nation = Territory.fromId(state.territoryId)?.let(::holderOf) ?: return
        val existing = state.scores[nation.uuid] ?: 0L
        val cap = Nodes.config.warzoneScoreCapMillis
        state.scores[nation.uuid] = if (cap == null) existing + elapsed else (existing + elapsed).coerceAtMost(cap)
        state.lastHeld[nation.uuid] = until
    }

    /** Most hold time first; ties go to the nation that held the zone most recently. */
    private fun rankingLocked(state: State): List<NationScore> = state.scores
        .mapNotNull { (nationId, millis) -> Nation.fromUuid(nationId)?.let { NationScore(it, millis) } }
        .sortedWith(
            compareByDescending<NationScore> { it.millis }
                .thenByDescending { state.lastHeld[it.nation.uuid] ?: Long.MIN_VALUE }
                .thenBy { it.nation.name },
        )

    private fun summaryLocked(state: State): Summary = Summary(
        state.territoryId,
        state.startMillis,
        state.endMillis,
        state.started,
        rankingLocked(state).firstOrNull(),
    )

    private fun hideBarLocked(bar: BossBar) {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            if (visibleBars[player.uuid] === bar) {
                visibleBars.remove(player.uuid)
                player.hideBossBar(bar)
            }
        }
    }

    private fun refreshBossBarsLocked() {
        val nowMillis = System.currentTimeMillis()
        states.values.filter { it.started }.forEach { updateBossBarLocked(it, nowMillis) }
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            showForPlayerLocked(player, Territory.fromPlayer(player))
        }
    }

    private fun showForPlayerLocked(player: Player, territory: Territory?) {
        val state = territory?.let { states[it.id] }?.takeIf { it.started }
        val desired = state?.let { it.bossBar ?: updateBossBarLocked(it, System.currentTimeMillis()) }
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

    private fun updateBossBarLocked(state: State, nowMillis: Long): BossBar {
        val leader = rankingLocked(state).firstOrNull()
        val remaining = state.endMillis?.let { " | ends in ${formatDuration((it - nowMillis).coerceAtLeast(0L))}" } ?: ""
        val title = if (leader == null) {
            "Warzone: no nation has held this territory$remaining"
        } else {
            "Warzone: ${leader.nation.name} — ${formatTime(leader.millis)}$remaining"
        }
        // The bar drains as the zone's time runs out.
        val end = state.endMillis
        val progress = if (end == null || end <= state.startMillis) {
            1f
        } else {
            ((end - nowMillis).toDouble() / (end - state.startMillis)).coerceIn(0.0, 1.0).toFloat()
        }
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
        lastSaveMillis = System.currentTimeMillis()
        if (!Nodes.config.save) return
        val root = buildJsonObject {
            putJsonObject("zones") {
                states.values.sortedBy { it.territoryId.toInt() }.forEach { state ->
                    putJsonObject(state.territoryId.toString()) {
                        put("start", state.startMillis)
                        put("end", state.endMillis)
                        put("started", state.started)
                        putJsonObject("scores") {
                            state.scores.entries.sortedBy { it.key.toString() }.forEach { (nationId, score) -> put(nationId.toString(), score) }
                        }
                        putJsonObject("lastHeld") {
                            state.lastHeld.entries.sortedBy { it.key.toString() }.forEach { (nationId, time) -> put(nationId.toString(), time) }
                        }
                    }
                }
            }
        }
        // A failed write must not abort the tick, which would skip awarding finished zones.
        runCatching {
            val path = Nodes.config.pathWarzone.toAbsolutePath()
            val parent = path.parent ?: return
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
            try {
                Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8)
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }.onFailure { error -> System.err.println("[Nodes] Failed to save warzones: ${error.message}") }
    }

    fun formatTime(millis: Long): String {
        val seconds = millis / 1000L
        return "%02d:%02d:%02d".format(seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
    }

    /** Coarse human duration, e.g. "2d 3h", "1h 20m", "45s". */
    fun formatDuration(millis: Long): String {
        val seconds = (millis + 999L) / 1000L
        val days = seconds / 86_400L
        val hours = (seconds % 86_400L) / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        return when {
            days > 0L -> "${days}d ${hours}h"
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m"
            else -> "${seconds}s"
        }
    }
}
