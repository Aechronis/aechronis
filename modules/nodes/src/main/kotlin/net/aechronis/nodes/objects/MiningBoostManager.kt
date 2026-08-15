package net.aechronis.nodes.objects

import com.google.gson.JsonObject
import net.aechronis.combat.tasks.HasteEffectManager
import net.aechronis.nodes.Nodes
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule

object MiningBoostManager {
    private const val HASTE_EFFECT_SOURCE = "nodes:mining_boost"
    private const val HASTE_MAX_MULTIPLIER = 2
    private const val MINING_BOOST_MAX_MULTIPLIER = 5
    private const val MILLIS_PER_TICK = 50L

    private data class ActiveBoost(
        val multiplier: Int,
        val startedAt: Long,
        val expiresAt: Long,
    ) {
        fun remaining(now: Long): Long = (expiresAt - now).coerceAtLeast(0L)
    }

    private var haste: ActiveBoost? = null
    private var miningBoost: ActiveBoost? = null
    private var task: Task? = null
    private var bossBar: BossBar? = null
    private var lastEffectUpdate: Long = 0L

    fun start() {
        if (task != null) return

        bossBar = BossBar.bossBar(
            Component.empty(),
            1f,
            BossBar.Color.GREEN,
            BossBar.Overlay.PROGRESS,
        )
        task = MinecraftServer.getSchedulerManager()
            .buildTask { update(System.currentTimeMillis()) }
            .delay(TaskSchedule.tick(1))
            .repeat(TaskSchedule.tick(20))
            .schedule()
    }

    fun stop() {
        task?.cancel()
        task = null
        clearEffectsAndBossBar()
        bossBar = null
    }

    /** Clear runtime state before a world reload. */
    @Synchronized
    fun reset() {
        haste = null
        miningBoost = null
        lastEffectUpdate = 0L
        clearEffectsAndBossBar()
    }

    fun onPlayerJoin(player: Player) {
        updatePlayer(player, System.currentTimeMillis())
    }

    fun onPlayerQuit(player: Player) {
        HasteEffectManager.clear(player, HASTE_EFFECT_SOURCE)
        bossBar?.let(player::hideBossBar)
    }

    @Synchronized
    fun addBoost(type: String, multiplier: Int, durationMillis: Long): Result<ActiveBoostInfo> {
        require(durationMillis > 0) { "Boost duration must be positive" }
        val maxMultiplier = if (type == "haste") HASTE_MAX_MULTIPLIER else MINING_BOOST_MAX_MULTIPLIER
        if (type != "haste" && type != "boost") return Result.failure(IllegalArgumentException("Unknown boost type"))
        if (multiplier !in 1..maxMultiplier) {
            return Result.failure(IllegalArgumentException("$type multiplier must be between 1 and $maxMultiplier"))
        }

        val now = System.currentTimeMillis()
        val old = if (type == "haste") haste else miningBoost
        val remaining = old?.remaining(now) ?: 0L
        if (old != null && old.expiresAt <= now) {
            if (type == "haste") haste = null else miningBoost = null
        }

        if (old != null && old.expiresAt > now && multiplier < old.multiplier) {
            return Result.failure(IllegalArgumentException("The new multiplier cannot be lower than the active multiplier"))
        }

        val retained = when {
            old == null || old.expiresAt <= now -> 0L
            multiplier == old.multiplier -> remaining
            else -> remaining / (multiplier - old.multiplier + 1L)
        }
        if (durationMillis > Long.MAX_VALUE - retained || now > Long.MAX_VALUE - (retained + durationMillis)) {
            return Result.failure(IllegalArgumentException("Boost duration is too large"))
        }
        val totalDuration = retained + durationMillis
        val updated = ActiveBoost(multiplier, now, now + totalDuration)
        lastEffectUpdate = 0L
        if (type == "haste") haste = updated else miningBoost = updated

        Nodes.needsSave = true
        update(now)
        return Result.success(updated.toInfo(now))
    }

    @Synchronized
    fun miningMultiplier(now: Long = System.currentTimeMillis()): Int {
        expire(now)
        return miningBoost?.multiplier ?: 1
    }

    /** Serialize only the global boost state; it is embedded in towns.json. */
    @Synchronized
    fun toJsonString(): String = "{\"haste\":${haste.toJson()},\"boost\":${miningBoost.toJson()}}"

    /** Load the optional global boost object from towns.json. */
    @Synchronized
    fun load(json: JsonObject?) {
        haste =
            json
                ?.get("haste")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.toActiveBoost(HASTE_MAX_MULTIPLIER)
        miningBoost =
            json
                ?.get("boost")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.toActiveBoost(MINING_BOOST_MAX_MULTIPLIER)
        expire(System.currentTimeMillis())
    }

    @Synchronized
    private fun update(now: Long) {
        expire(now)

        val activeHaste = haste
        if (activeHaste != null && (now - lastEffectUpdate >= 900L || lastEffectUpdate == 0L)) {
            MinecraftServer.getConnectionManager().onlinePlayers.forEach { updatePlayerEffect(it, activeHaste, now) }
            lastEffectUpdate = now
        } else if (activeHaste == null && lastEffectUpdate != 0L) {
            MinecraftServer.getConnectionManager().onlinePlayers.forEach {
                HasteEffectManager.clear(it, HASTE_EFFECT_SOURCE)
            }
            lastEffectUpdate = 0L
        }

        updateBossBar(now)
    }

    @Synchronized
    private fun updatePlayer(player: Player, now: Long) {
        expire(now)
        haste?.let { updatePlayerEffect(player, it, now) }
            ?: HasteEffectManager.clear(player, HASTE_EFFECT_SOURCE)
        updateBossBar(now)
    }

    private fun updatePlayerEffect(player: Player, boost: ActiveBoost, now: Long) {
        val remainingTicks = ((boost.remaining(now) + MILLIS_PER_TICK - 1L) / MILLIS_PER_TICK)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        HasteEffectManager.set(
            player,
            HASTE_EFFECT_SOURCE,
            amplifier = boost.multiplier - 1,
            durationTicks = remainingTicks,
        )
    }

    private fun expire(now: Long) {
        if (haste?.expiresAt?.let { it <= now } == true) {
            haste = null
            Nodes.needsSave = true
        }
        if (miningBoost?.expiresAt?.let { it <= now } == true) {
            miningBoost = null
            Nodes.needsSave = true
        }
    }

    private fun updateBossBar(now: Long) {
        val bar = bossBar ?: return
        val activeHaste = haste
        val activeMiningBoost = miningBoost
        if (activeHaste == null && activeMiningBoost == null) {
            MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.hideBossBar(bar) }
            return
        }

        val title = buildList {
            activeHaste?.let { add("Haste ${it.multiplier}x (${formatTime(it.remaining(now))})") }
            activeMiningBoost?.let { add("Mining Boost ${it.multiplier}x (${formatTime(it.remaining(now))})") }
        }.joinToString(" | ")
        bar.name(Component.text(title, NamedTextColor.GREEN))

        val progress = listOfNotNull(activeHaste, activeMiningBoost)
            .map { boost ->
                val total = (boost.expiresAt - boost.startedAt).coerceAtLeast(1L)
                boost.remaining(now).toFloat() / total.toFloat()
            }
            .minOrNull()
            ?.coerceIn(0f, 1f)
            ?: 1f
        bar.progress(progress)
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.showBossBar(bar) }
    }

    private fun clearEffectsAndBossBar() {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            HasteEffectManager.clear(player, HASTE_EFFECT_SOURCE)
            bossBar?.let(player::hideBossBar)
        }
    }

    data class ActiveBoostInfo(
        val multiplier: Int,
        val remainingMillis: Long,
    )

    private fun ActiveBoost.toInfo(now: Long) = ActiveBoostInfo(multiplier, remaining(now))

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = ((milliseconds.coerceAtLeast(0L) + 999L) / 1000L)
        return when {
            totalSeconds >= 86400L -> "${totalSeconds / 86400L}d ${(totalSeconds % 86400L) / 3600L}h"
            totalSeconds >= 3600L -> "${totalSeconds / 3600L}h ${(totalSeconds % 3600L) / 60L}m"
            totalSeconds >= 60L -> "${totalSeconds / 60L}m ${totalSeconds % 60L}s"
            else -> "${totalSeconds}s"
        }
    }

    private fun ActiveBoost?.toJson(): String = this?.let {
        "{\"multiplier\":${it.multiplier},\"startedAt\":${it.startedAt},\"expiresAt\":${it.expiresAt}}"
    } ?: "null"

    private fun JsonObject.toActiveBoost(maxMultiplier: Int): ActiveBoost? {
        val multiplier = get("multiplier")?.asInt ?: return null
        val expiresAt = get("expiresAt")?.asLong ?: return null
        if (multiplier !in 1..maxMultiplier || expiresAt <= System.currentTimeMillis()) return null
        val startedAt = get("startedAt")?.asLong ?: System.currentTimeMillis()
        return ActiveBoost(multiplier, startedAt.coerceAtMost(expiresAt), expiresAt)
    }
}
