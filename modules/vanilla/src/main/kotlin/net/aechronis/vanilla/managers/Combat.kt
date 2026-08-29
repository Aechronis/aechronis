package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.CombatListener
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

object Combat {
    // player uuid -> epoch millis when the combat tag expires
    private val expiresAt = ConcurrentHashMap<UUID, Long>()
    private val bossBars = ConcurrentHashMap<UUID, BossBar>()

    fun init() {
        val timeStart = System.currentTimeMillis()
        CombatListener.init()
        ModuleScheduler
            .buildTask(::tick)
            .repeat(TaskSchedule.seconds(Vanilla.config.combatTickSeconds))
            .schedule()
        val timeEnd = System.currentTimeMillis()
        println("├─ Combat enabled in ${timeEnd - timeStart}ms")
    }

    fun tag(
        a: Player,
        b: Player,
    ) {
        tagOne(a)
        tagOne(b)
    }

    fun isInCombat(player: Player): Boolean = (expiresAt[player.uuid] ?: 0L) > System.currentTimeMillis()

    fun clear(player: Player) {
        expiresAt.remove(player.uuid)
        bossBars.remove(player.uuid)?.let { player.hideBossBar(it) }
    }

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

    fun shutdown() {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(::clear)
        expiresAt.clear()
        bossBars.clear()
    }

    private fun tagOne(player: Player) {
        val wasInCombat = isInCombat(player)
        expiresAt[player.uuid] = System.currentTimeMillis() + Vanilla.config.combatDurationSeconds * 1000

        if (!wasInCombat) {
            val bar = createBossBar(Vanilla.config.combatDurationSeconds * 1000)
            bossBars[player.uuid] = bar
            player.showBossBar(bar)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val durationMs = Vanilla.config.combatDurationSeconds * 1000

        for ((uuid, expiry) in expiresAt) {
            val player =
                MinecraftServer
                    .getConnectionManager()
                    .onlinePlayers
                    .firstOrNull { it.uuid == uuid } ?: continue
            val bar = bossBars[uuid] ?: continue
            val remaining = expiry - now

            if (remaining <= 0) {
                expiresAt.remove(uuid)
                bossBars.remove(uuid)
                player.hideBossBar(bar)
                player.sendMessage(Component.text("You have left combat", NamedTextColor.GREEN))
                continue
            }

            bar.progress((remaining.toFloat() / durationMs).coerceIn(0f, 1f))
            bar.name(Component.text("Combat: ${(remaining / 1000) + 1}s", NamedTextColor.RED))
        }
    }

    private fun createBossBar(remainingMillis: Long): BossBar {
        val durationMillis = (Vanilla.config.combatDurationSeconds * 1000).coerceAtLeast(1L)
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
