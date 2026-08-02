package net.aechronis.logger.repos

import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.EntityChange
import net.aechronis.logger.objects.EntityChangeAction
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.utils.placeholders
import net.aechronis.logger.utils.setNullableBytes
import net.aechronis.logger.utils.setNullableString
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EntityChange(
    private val database: Database,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val table = database.entityChangeTableName
    private val columns =
        "id, ts, player_uuid, player_name, entity_uuid, entity_type, action, instance_uuid, x, y, z, yaw, pitch, " +
            "velocity_x, velocity_y, velocity_z, tag_data, source, origin, rolled_back"
    private val pendingWrites = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()

    fun insertAsync(change: EntityChange): CompletableFuture<Void> {
        val future =
            CompletableFuture.runAsync({
                database.dataSource.connection.use { connection ->
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO "$table"
                                (ts, player_uuid, player_name, entity_uuid, entity_type, action, instance_uuid,
                                 x, y, z, yaw, pitch, velocity_x, velocity_y, velocity_z, tag_data, source, origin)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, change.timestamp)
                            statement.setNullableString(2, change.playerUuid?.toString())
                            statement.setNullableString(3, change.playerName)
                            statement.setString(4, change.entityUuid.toString())
                            statement.setString(5, change.entityType)
                            statement.setString(6, change.action.value)
                            statement.setString(7, change.instanceUuid.toString())
                            statement.setDouble(8, change.position.x())
                            statement.setDouble(9, change.position.y())
                            statement.setDouble(10, change.position.z())
                            statement.setFloat(11, change.position.yaw())
                            statement.setFloat(12, change.position.pitch())
                            statement.setDouble(13, change.velocity.x())
                            statement.setDouble(14, change.velocity.y())
                            statement.setDouble(15, change.velocity.z())
                            statement.setNullableBytes(16, change.tagData)
                            statement.setString(17, change.source)
                            statement.setString(18, change.origin)
                            statement.executeUpdate()
                        }
                }
            }, executor)
        pendingWrites += future
        future.whenComplete { _, _ -> pendingWrites -= future }
        return future
    }

    fun flushAsync(): CompletableFuture<Void> = CompletableFuture.allOf(*pendingWrites.toTypedArray())

    fun searchForOperationAsync(
        params: LookupParams,
        targetTs: Long,
        actions: Set<EntityChangeAction>?,
        rolledBack: Boolean,
        instanceUuid: UUID,
        center: Pos,
        limit: Int,
    ): CompletableFuture<List<EntityChange>> =
        flushAsync().thenApplyAsync({
            val sql = StringBuilder("SELECT $columns FROM \"$table\" WHERE ts >= ? AND rolled_back = ? AND instance_uuid = ?")
            val args = mutableListOf<Any>(targetTs, rolledBack, instanceUuid.toString())
            params.until?.let {
                sql.append(" AND ts <= ?")
                args += it
            }
            if (params.users.isNotEmpty()) {
                sql.append(" AND LOWER(player_name) IN (${placeholders(params.users.size)})")
                params.users.forEach { args += it.lowercase() }
            }
            params.source?.let {
                sql.append(" AND LOWER(source) = ?")
                args += it.lowercase()
            }
            params.origin?.let {
                sql.append(" AND LOWER(origin) = ?")
                args += it.lowercase()
            }
            params.radius?.let { radius ->
                sql.append(" AND FLOOR(x) BETWEEN ? AND ? AND FLOOR(y) BETWEEN ? AND ? AND FLOOR(z) BETWEEN ? AND ?")
                args += center.blockX() - radius
                args += center.blockX() + radius
                args += center.blockY() - radius
                args += center.blockY() + radius
                args += center.blockZ() - radius
                args += center.blockZ() + radius
            }
            params.chunkRadius?.let { chunkRadius ->
                val expand = chunkRadius - 1
                val minX = ((center.blockX() shr 4) - expand) shl 4
                val maxX = (((center.blockX() shr 4) + expand) shl 4) + 15
                val minZ = ((center.blockZ() shr 4) - expand) shl 4
                val maxZ = (((center.blockZ() shr 4) + expand) shl 4) + 15
                sql.append(" AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")
                args += minX
                args += maxX
                args += minZ
                args += maxZ
            }
            actions?.let {
                sql.append(" AND action IN (${placeholders(it.size)})")
                it.forEach { action -> args += action.value }
            }
            if (params.include.isNotEmpty()) {
                sql.append(" AND entity_type IN (${placeholders(params.include.size)})")
                params.include.forEach { args += it }
            }
            if (params.exclude.isNotEmpty()) {
                sql.append(" AND entity_type NOT IN (${placeholders(params.exclude.size)})")
                params.exclude.forEach { args += it }
            }
            sql.append(if (rolledBack) " ORDER BY ts ASC, id ASC LIMIT ?" else " ORDER BY ts DESC, id DESC LIMIT ?")
            args += if (limit == Int.MAX_VALUE) limit else limit + 1
            val rows = mutableListOf<EntityChange>()
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(sql.toString()).use { statement ->
                    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeQuery().use { results -> while (results.next()) rows += mapRow(results) }
                }
            }
            rows
        }, executor)

    private fun mapRow(results: ResultSet): EntityChange =
        EntityChange(
            id = results.getLong("id"),
            timestamp = results.getLong("ts"),
            playerUuid = results.getString("player_uuid")?.let(UUID::fromString),
            playerName = results.getString("player_name"),
            entityUuid = UUID.fromString(results.getString("entity_uuid")),
            entityType = results.getString("entity_type"),
            action = EntityChangeAction.fromValue(results.getString("action")),
            instanceUuid = UUID.fromString(results.getString("instance_uuid")),
            position =
                Pos(
                    results.getDouble("x"),
                    results.getDouble("y"),
                    results.getDouble("z"),
                    results.getFloat("yaw"),
                    results.getFloat("pitch"),
                ),
            velocity = Vec(results.getDouble("velocity_x"), results.getDouble("velocity_y"), results.getDouble("velocity_z")),
            tagData = results.getBytes("tag_data"),
            source = results.getString("source"),
            origin = results.getString("origin"),
            rolledBack = results.getBoolean("rolled_back"),
        )

    override fun close() {
        executor.close()
    }
}
