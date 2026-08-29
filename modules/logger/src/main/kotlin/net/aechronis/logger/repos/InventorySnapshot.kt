package net.aechronis.logger.repos

import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.InventorySnapshot
import net.aechronis.logger.objects.InventorySnapshotAction
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata
import net.aechronis.logger.utils.shutdownExecutor
import net.minestom.server.item.ItemStack
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock

class InventorySnapshot(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.inventorySnapshotTableName
    private val pendingWrites = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()
    private val lifecycleLock = ReentrantReadWriteLock()

    @Volatile
    private var closed = false

    private val selectColumns = "id, ts, player_uuid, player_name, action, inventory_data, source, origin"

    private val insertSql =
        """
        INSERT INTO "$table"
            (ts, player_uuid, player_name, action, inventory_data, source, origin)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    /**
     * Submits work only while the repository is open. The read lock makes admission atomic with
     * [close], so an executor can never be shut down between the lifecycle check and submission.
     */
    private fun submit(task: () -> Unit): CompletableFuture<Void> {
        lifecycleLock.readLock().lock()
        try {
            if (closed) return CompletableFuture.failedFuture(IllegalStateException("inventory snapshot repository is closed"))
            return CompletableFuture.runAsync(task, executor)
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    private fun <T> supply(task: () -> T): CompletableFuture<T> {
        lifecycleLock.readLock().lock()
        try {
            if (closed) return CompletableFuture.failedFuture(IllegalStateException("inventory snapshot repository is closed"))
            return CompletableFuture.supplyAsync(task, executor)
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    fun insertAsync(snapshot: InventorySnapshot): CompletableFuture<Void> {
        val future = submit { insert(snapshot) }
        pendingWrites += future
        future.whenComplete { _, _ -> pendingWrites -= future }
        return future
    }

    fun flushAsync(): CompletableFuture<Void> = CompletableFuture.allOf(*pendingWrites.toTypedArray())

    fun findByPlayerNameAsync(
        playerName: String,
        limit: Int,
        offset: Int = 0,
    ): CompletableFuture<List<InventorySnapshot>> {
        require(limit > 0) { "snapshot limit must be positive" }
        require(offset >= 0) { "snapshot offset cannot be negative" }
        return flushAsync().thenCompose {
            supply {
                val snapshots = mutableListOf<InventorySnapshot>()
                database.dataSource.connection.use { connection ->
                    connection
                        .prepareStatement(
                            """
                            SELECT $selectColumns
                            FROM "$table"
                            WHERE player_uuid = (
                                SELECT player_uuid
                                FROM "$table"
                                WHERE LOWER(player_name) = ?
                                ORDER BY ts DESC, id DESC
                                LIMIT 1
                            )
                              AND action IN (?, ?)
                              AND inventory_data IS NOT NULL
                            ORDER BY ts DESC, id DESC
                            LIMIT ? OFFSET ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, playerName.lowercase())
                            statement.setString(2, InventorySnapshotAction.DEATH.value)
                            statement.setString(3, InventorySnapshotAction.LOGOUT.value)
                            statement.setInt(4, limit)
                            statement.setInt(5, offset)
                            statement.executeQuery().use { results ->
                                while (results.next()) snapshots += mapRow(results)
                            }
                        }
                }
                snapshots
            }
        }
    }

    fun findByIdAsync(id: Long): CompletableFuture<InventorySnapshot?> =
        flushAsync().thenCompose {
            supply {
                database.dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT $selectColumns FROM \"$table\" WHERE id = ?").use { statement ->
                        statement.setLong(1, id)
                        statement.executeQuery().use { results -> if (results.next()) mapRow(results) else null }
                    }
                }
            }
        }

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

    private fun mapRow(results: ResultSet): InventorySnapshot =
        InventorySnapshot(
            id = results.getLong("id"),
            timestamp = results.getLong("ts"),
            playerUuid = UUID.fromString(results.getString("player_uuid")),
            playerName = results.getString("player_name"),
            action = InventorySnapshotAction.fromValue(results.getString("action")),
            items = ItemCodec.decodeInventory(results.getBytes("inventory_data")),
            source = results.getString("source"),
            origin = results.getString("origin"),
        )

    override fun close() {
        lifecycleLock.writeLock().lock()
        try {
            closed = true
            shutdownExecutor(executor, "inventory snapshot repository")
        } finally {
            lifecycleLock.writeLock().unlock()
        }
    }
}
