package net.aechronis.combat.managers

import net.aechronis.combat.Combat
import net.aechronis.combat.listeners.CombatTagListener
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.TaskSchedule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CombatTags {
    // Mutations share this object's monitor so expiry and bossbar cleanup cannot interleave.
    // player uuid -> epoch millis when the combat tag expires
    private val expiresAt = ConcurrentHashMap<UUID, Long>()
    private val bossBars = ConcurrentHashMap<UUID, BossBar>()

    fun init() {
        CombatTagListener.init()
        ModuleScheduler
            .buildTask(::tick)
            .repeat(TaskSchedule.seconds(1))
            .schedule()
    }

    fun tag(
        a: Player,
        b: Player,
    ) {
        tagOne(a)
        tagOne(b)
    }

    fun isInCombat(player: Player): Boolean = (expiresAt[player.uuid] ?: 0L) > System.currentTimeMillis()

    @Synchronized
    fun clear(player: Player) {
        expiresAt.remove(player.uuid)
        bossBars.remove(player.uuid)?.let { player.hideBossBar(it) }
    }

    @Synchronized
    internal fun captureTransientState(now: Long = System.currentTimeMillis()): ByteArray {
        val active =
            expiresAt.entries
                .filter { (_, expiry) -> expiry > now }
                .sortedBy { (uuid, _) -> uuid.toString() }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(TRANSIENT_STATE_VERSION)
                output.writeInt(active.size)
                active.forEach { (uuid, expiry) ->
                    output.writeLong(uuid.mostSignificantBits)
                    output.writeLong(uuid.leastSignificantBits)
                    output.writeLong(expiry)
                }
            }
            bytes.toByteArray()
        }
    }

    @Synchronized
    internal fun restoreTransientState(
        payload: ByteArray?,
        now: Long = System.currentTimeMillis(),
        onlinePlayers: Collection<Player> = MinecraftServer.getConnectionManager().onlinePlayers,
    ) {
        if (payload == null) return
        val restored = decodeTransientState(payload)
        val playersById = onlinePlayers.associateBy { it.uuid }

        bossBars.forEach { (uuid, bar) -> playersById[uuid]?.hideBossBar(bar) }
        bossBars.clear()
        expiresAt.clear()
        restored.forEach { (uuid, expiry) ->
            if (expiry <= now) return@forEach
            val player = playersById[uuid] ?: return@forEach
            expiresAt[uuid] = expiry
            val bar = createBossBar(expiry - now)
            bossBars[uuid] = bar
            player.showBossBar(bar)
        }
    }

    @Synchronized
    fun shutdown() {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(::clear)
        expiresAt.clear()
        bossBars.clear()
    }

    @Synchronized
    private fun tagOne(player: Player) {
        val durationMillis = Combat.config.combatDurationSeconds * 1000
        expiresAt[player.uuid] = System.currentTimeMillis() + durationMillis

        // An expired tag's bar can still be visible until the next tick. Reuse it, or it is orphaned.
        val bar =
            bossBars[player.uuid] ?: createBossBar(durationMillis).also {
                bossBars[player.uuid] = it
                player.showBossBar(it)
            }
        updateBossBar(bar, durationMillis)
    }

    @Synchronized
    private fun tick() {
        val now = System.currentTimeMillis()

        for ((uuid, expiry) in expiresAt) {
            val player =
                MinecraftServer
                    .getConnectionManager()
                    .onlinePlayers
                    .firstOrNull { it.uuid == uuid } ?: continue
            val bar = bossBars[uuid] ?: continue
            val remaining = expiry - now

            if (remaining <= 0) {
                clear(player)
                player.sendMessage(Component.text("You have left combat", NamedTextColor.GREEN))
                continue
            }

            updateBossBar(bar, remaining)
        }
    }

    private fun updateBossBar(
        bar: BossBar,
        remainingMillis: Long,
    ) {
        val durationMillis = (Combat.config.combatDurationSeconds * 1000).coerceAtLeast(1L)
        bar.progress((remainingMillis.toFloat() / durationMillis).coerceIn(0f, 1f))
        bar.name(Component.text("Combat: ${(remainingMillis / 1000) + 1}s", NamedTextColor.RED))
    }

    private fun createBossBar(remainingMillis: Long): BossBar {
        val durationMillis = (Combat.config.combatDurationSeconds * 1000).coerceAtLeast(1L)
        return BossBar.bossBar(
            Component.text("Combat: ${(remainingMillis / 1000) + 1}s", NamedTextColor.RED),
            (remainingMillis.toFloat() / durationMillis).coerceIn(0f, 1f),
            BossBar.Color.RED,
            BossBar.Overlay.PROGRESS,
        )
    }

    private fun decodeTransientState(payload: ByteArray): Map<UUID, Long> =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == TRANSIENT_STATE_VERSION) { "Unsupported combat-tag transient-state version" }
            val size = input.readInt()
            require(size in 0..MAX_TRANSIENT_ENTRIES) { "Invalid combat-tag transient-state size: $size" }
            buildMap(size) {
                repeat(size) {
                    val uuid = UUID(input.readLong(), input.readLong())
                    val expiry = input.readLong()
                    require(expiry >= 0L) { "Invalid combat-tag expiry for $uuid" }
                    require(put(uuid, expiry) == null) { "Duplicate combat-tag entry for $uuid" }
                }
                require(input.available() == 0) { "Trailing combat-tag transient-state data" }
            }
        }

    private const val TRANSIENT_STATE_VERSION = 1
    private const val MAX_TRANSIENT_ENTRIES = 10_000
}
