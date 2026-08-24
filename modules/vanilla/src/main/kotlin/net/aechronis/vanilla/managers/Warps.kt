package net.aechronis.vanilla.managers

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Named player teleport destinations persisted as JSON. */
object Warps {
    const val COOLDOWN_BYPASS_PERMISSION = "warp.cooldown.bypass"

    data class SavedWarp(
        val name: String,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float = 0f,
        val pitch: Float = 0f,
    )

    enum class TeleportResult {
        SUCCESS,
        NOT_FOUND,
        WORLD_UNAVAILABLE,
        ON_COOLDOWN,
        WARPING,
        ALREADY_WARPING,
    }

    private data class PendingWarp(
        val warp: SavedWarp,
        val instance: Instance,
    )

    private val warps = linkedMapOf<String, SavedWarp>()
    private val lastUse = ConcurrentHashMap<UUID, Long>()
    private val pendingWarps = ConcurrentHashMap<UUID, PendingWarp>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var file: Path

    fun init(path: Path) {
        file = path
        Files.createDirectories(path.parent)
        load()
    }

    fun saveAll() = save()

    fun names(): List<String> = synchronized(warps) { warps.values.map(SavedWarp::name).sorted() }

    fun set(
        name: String,
        player: Player,
    ): Boolean {
        if (name.isBlank()) return false
        val instance = player.instance ?: return false
        val position = player.position
        val warp =
            SavedWarp(
                name = name,
                world = instance.getDimensionName(),
                x = position.x(),
                y = position.y(),
                z = position.z(),
                yaw = position.yaw(),
                pitch = position.pitch(),
            )
        synchronized(warps) {
            warps[name.key()] = warp
        }
        save()
        return true
    }

    fun remove(name: String): Boolean {
        val removed = synchronized(warps) { warps.remove(name.key()) != null }
        if (removed) save()
        return removed
    }

    fun teleport(
        player: Player,
        name: String,
        onComplete: (String) -> Unit = {},
    ): TeleportResult {
        val warp = synchronized(warps) { warps[name.key()] } ?: return TeleportResult.NOT_FOUND
        val instance = instance(warp.world) ?: return TeleportResult.WORLD_UNAVAILABLE
        if (onCooldown(player)) return TeleportResult.ON_COOLDOWN

        val delayMillis = cooldownMillis(player)
        if (delayMillis == 0L) {
            performTeleport(player, warp, instance)
            return TeleportResult.SUCCESS
        }

        val pending = PendingWarp(warp, instance)
        if (pendingWarps.putIfAbsent(player.uuid, pending) != null) return TeleportResult.ALREADY_WARPING
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                if (pendingWarps.remove(player.uuid, pending)) {
                    performTeleport(player, pending.warp, pending.instance)
                    onComplete(pending.warp.name)
                }
            }.delay(TaskSchedule.millis(delayMillis))
            .schedule()
        return TeleportResult.WARPING
    }

    private fun performTeleport(
        player: Player,
        warp: SavedWarp,
        instance: Instance,
    ) {
        val position = Pos(warp.x, warp.y, warp.z, warp.yaw, warp.pitch)
        if (player.instance === instance) player.teleport(position) else player.setInstance(instance, position)
        lastUse[player.uuid] = System.currentTimeMillis()
    }

    fun remainingCooldownMillis(player: Player): Long {
        if (player.hasPermission(COOLDOWN_BYPASS_PERMISSION)) return 0
        val last = lastUse[player.uuid] ?: return 0
        return (cooldownMillis(player) - (System.currentTimeMillis() - last)).coerceAtLeast(0)
    }

    private fun cooldownMillis(player: Player): Long =
        if (player.hasPermission(COOLDOWN_BYPASS_PERMISSION)) 0 else Vanilla.config.warpCooldownSeconds.coerceAtLeast(0) * 1_000L

    private fun onCooldown(player: Player): Boolean = remainingCooldownMillis(player) > 0

    private fun instance(world: String): Instance? =
        MinecraftServer.getInstanceManager().instances.firstOrNull { it.getDimensionName() == world }

    private fun load() {
        synchronized(warps) {
            warps.clear()
            if (!Files.exists(file)) return
            runCatching {
                Files.newBufferedReader(file).use { reader ->
                    val type = object : TypeToken<List<SavedWarp>>() {}.type
                    gson.fromJson<List<SavedWarp>?>(reader, type).orEmpty()
                }
            }.onSuccess { saved ->
                saved.forEach { warp ->
                    if (valid(warp) && warps.putIfAbsent(warp.name.key(), warp) == null) return@forEach
                    System.err.println("Skipping invalid or duplicate warp '${warp.name}' in $file")
                }
            }.onFailure { error ->
                System.err.println("Failed to load warps: ${error.message}")
            }
        }
    }

    private fun save() {
        if (!::file.isInitialized) return
        val saved = synchronized(warps) { warps.values.toList() }
        runCatching {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(file).use { writer -> gson.toJson(saved, writer) }
        }.onFailure { error ->
            System.err.println("Failed to save warps: ${error.message}")
        }
    }

    private fun valid(warp: SavedWarp): Boolean =
        warp.name.isNotBlank() &&
            warp.world.isNotBlank() &&
            warp.x.isFinite() &&
            warp.y.isFinite() &&
            warp.z.isFinite() &&
            warp.yaw.isFinite() &&
            warp.pitch.isFinite()

    private fun String.key(): String = lowercase()
}
