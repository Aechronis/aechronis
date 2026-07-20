package net.aechronis.logger.db

import net.aechronis.logger.objects.LogMetadata
import net.aechronis.logger.objects.StorageChange
import net.aechronis.logger.objects.StorageChangeAction
import net.aechronis.logger.utils.ItemCodec
import net.minestom.server.item.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StorageChangeRepository(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.storageTableName

    private val insertSql =
        """
        INSERT INTO `$table`
            (ts, player_uuid, player_name, storage_id, action, item_data, amount, source, origin)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    fun insertAsync(change: StorageChange): CompletableFuture<Void> = CompletableFuture.runAsync({ insert(change) }, executor)

    fun withdrawAsync(
        storageId: String,
        item: ItemStack,
        amount: Int,
        playerUuid: UUID? = null,
        playerName: String? = null,
        source: String = LogMetadata.LOGGER,
        origin: String = LogMetadata.LOGGER,
    ): CompletableFuture<Void> =
        insertAsync(
            StorageChange(
                timestamp = System.currentTimeMillis(),
                storageId = storageId,
                action = StorageChangeAction.WITHDRAW,
                item = item,
                amount = amount,
                playerUuid = playerUuid,
                playerName = playerName,
                source = source,
                origin = origin,
            ),
        )

    fun depositAsync(
        storageId: String,
        item: ItemStack,
        amount: Int,
        playerUuid: UUID? = null,
        playerName: String? = null,
        source: String = LogMetadata.LOGGER,
        origin: String = LogMetadata.LOGGER,
    ): CompletableFuture<Void> =
        insertAsync(
            StorageChange(
                timestamp = System.currentTimeMillis(),
                storageId = storageId,
                action = StorageChangeAction.DEPOSIT,
                item = item,
                amount = amount,
                playerUuid = playerUuid,
                playerName = playerName,
                source = source,
                origin = origin,
            ),
        )

    private fun insert(change: StorageChange) {
        require(change.amount > 0) { "storage change amount must be positive" }
        database.dataSource.connection.use { conn ->
            conn.prepareStatement(insertSql).use { ps ->
                ps.setLong(1, change.timestamp)
                ps.setNullableString(2, change.playerUuid?.toString())
                ps.setNullableString(3, change.playerName)
                ps.setString(4, change.storageId)
                ps.setString(5, change.action.value)
                ps.setBytes(6, ItemCodec.encodeItem(change.item))
                ps.setInt(7, change.amount)
                ps.setString(8, change.source)
                ps.setString(9, change.origin)
                ps.executeUpdate()
            }
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
