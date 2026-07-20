package net.aechronis.logger.db

import net.aechronis.logger.objects.InventorySnapshot
import net.aechronis.logger.objects.InventorySnapshotAction
import net.aechronis.logger.objects.LogMetadata
import net.aechronis.logger.utils.ItemCodec
import net.minestom.server.item.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InventorySnapshotRepository(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.inventorySnapshotTableName

    private val insertSql =
        """
        INSERT INTO `$table`
            (ts, player_uuid, player_name, action, inventory_data, source, origin)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    fun insertAsync(snapshot: InventorySnapshot): CompletableFuture<Void> = CompletableFuture.runAsync({ insert(snapshot) }, executor)

    fun deathAsync(
        playerUuid: UUID,
        playerName: String,
        items: List<ItemStack>,
        source: String = LogMetadata.LOGGER,
        origin: String = LogMetadata.LOGGER,
    ): CompletableFuture<Void> = insertActionAsync(playerUuid, playerName, InventorySnapshotAction.DEATH, items, source, origin)

    fun loginAsync(
        playerUuid: UUID,
        playerName: String,
        items: List<ItemStack>,
        source: String = LogMetadata.LOGGER,
        origin: String = LogMetadata.LOGGER,
    ): CompletableFuture<Void> = insertActionAsync(playerUuid, playerName, InventorySnapshotAction.LOGIN, items, source, origin)

    fun logoutAsync(
        playerUuid: UUID,
        playerName: String,
        items: List<ItemStack>,
        source: String = LogMetadata.LOGGER,
        origin: String = LogMetadata.LOGGER,
    ): CompletableFuture<Void> = insertActionAsync(playerUuid, playerName, InventorySnapshotAction.LOGOUT, items, source, origin)

    private fun insertActionAsync(
        playerUuid: UUID,
        playerName: String,
        action: InventorySnapshotAction,
        items: List<ItemStack>,
        source: String,
        origin: String,
    ): CompletableFuture<Void> =
        insertAsync(
            InventorySnapshot(
                timestamp = System.currentTimeMillis(),
                playerUuid = playerUuid,
                playerName = playerName,
                action = action,
                items = items,
                source = source,
                origin = origin,
            ),
        )

    private fun insert(snapshot: InventorySnapshot) {
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(insertSql).use { ps ->
                ps.setLong(1, snapshot.timestamp)
                ps.setString(2, snapshot.playerUuid.toString())
                ps.setString(3, snapshot.playerName)
                ps.setString(4, snapshot.action.value)
                ps.setBytes(5, ItemCodec.encodeInventory(snapshot.items))
                ps.setString(6, snapshot.source)
                ps.setString(7, snapshot.origin)
                ps.executeUpdate()
            }
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
