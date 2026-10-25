package net.aechronis.killshop

import kotlinx.serialization.json.Json
import net.aechronis.utils.JsonRegistryFile
import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Kill points belong to this module; old player NBT is read only during migration. */
object KillShopPoints {
    private val balances = ConcurrentHashMap<UUID, Int>()
    private val failedMigrations = ConcurrentHashMap<UUID, Path>()
    private val storage = JsonRegistryFile("kill-shop points")
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "killshop-points").apply { isDaemon = true } }
    private var pending = CompletableFuture.completedFuture<Void>(null)

    fun init(
        path: Path,
        legacyPlayers: Path,
    ) {
        storage.load(path, balances) { contents ->
            Json.decodeFromString<Map<String, Int>>(contents).mapKeys { UUID.fromString(it.key) }
        }
        check(storage.canSave) { "Cannot load kill-shop points from $path" }
        if (Files.exists(legacyPlayers)) {
            Files.list(legacyPlayers).use { files ->
                files.filter { it.fileName.toString().endsWith(".dat") }.forEach { file ->
                    val uuid = runCatching { UUID.fromString(file.fileName.toString().removeSuffix(".dat")) }.getOrNull()
                    if (uuid != null && !balances.containsKey(uuid)) {
                        migrateLegacy(uuid, file)
                    }
                }
            }
        }
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .filterNot { failedMigrations.containsKey(it.uuid) }
            .forEach(::balance)
        // Migrate offline players before Vanilla's next save removes the old Points field.
        save(snapshot()).join()
    }

    fun balance(player: Player): Int {
        requireMigration(player.uuid)
        return balances.computeIfAbsent(player.uuid) { player.getTag(KillShop.POINTS_TAG) ?: 0 }
    }

    fun setBalance(
        player: Player,
        points: Int,
    ) {
        requireMigration(player.uuid)
        balances[player.uuid] = points
        player.setTag(KillShop.POINTS_TAG, points)
    }

    private fun requireMigration(uuid: UUID) {
        val file = failedMigrations[uuid] ?: return
        check(migrateLegacy(uuid, file)) { "Kill-shop points for $uuid are unavailable until legacy data at $file can be read" }
        try {
            // A repaired player file may be saved by Vanilla without Points immediately after login.
            save(snapshot()).join()
        } catch (error: Exception) {
            failedMigrations[uuid] = file
            throw error
        }
    }

    @Synchronized
    private fun migrateLegacy(
        uuid: UUID,
        file: Path,
    ): Boolean {
        if (balances.containsKey(uuid)) {
            failedMigrations.remove(uuid)
            return true
        }
        return try {
            val points =
                Files.newInputStream(file).use { input ->
                    BinaryTagIO
                        .reader()
                        .readNamed(input, BinaryTagIO.Compression.GZIP)
                        .value
                        .getInt("Points", 0)
                }
            balances[uuid] = points
            failedMigrations.remove(uuid)
            true
        } catch (error: Exception) {
            failedMigrations[uuid] = file
            System.err.println(
                "Failed to migrate kill-shop points for $uuid from $file: ${error.message}. " +
                    "This account's points remain unavailable until its legacy data can be read.",
            )
            false
        }
    }

    fun snapshot(): Map<String, Int> = balances.entries.associate { it.key.toString() to it.value }

    @Synchronized
    fun save(snapshot: Map<String, Int>): CompletableFuture<Void> {
        pending = pending.handle { _, _ -> null }.thenRunAsync({ storage.save { Json.encodeToString(snapshot) } }, writer)
        return pending.copy()
    }

    fun shutdown() {
        try {
            pending.join()
        } finally {
            writer.shutdown()
            balances.clear()
            failedMigrations.clear()
        }
    }
}
