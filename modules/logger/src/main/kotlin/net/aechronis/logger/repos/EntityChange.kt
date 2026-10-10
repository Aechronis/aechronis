package net.aechronis.logger.repos

import net.aechronis.logger.db.Database
import net.aechronis.logger.objects.EntityChange
import net.aechronis.logger.objects.EntityChangeAction
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.utils.AsyncWriteGate
import net.aechronis.logger.utils.appendLogFilters
import net.aechronis.logger.utils.bindAll
import net.aechronis.logger.utils.chunkBounds
import net.aechronis.logger.utils.placeholders
import net.aechronis.logger.utils.radiusBounds
import net.aechronis.logger.utils.setNullableBytes
import net.aechronis.logger.utils.setNullableString
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
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
    private val writeGate = AsyncWriteGate(executor, "entity change repository")

    fun insertAsync(change: EntityChange): CompletableFuture<Void> =
        writeGate.submit {
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
        }

    fun flushAsync(): CompletableFuture<Void> = writeGate.flushAsync()

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
            sql.appendLogFilters(args, params.users, params.source, params.origin, until = params.until)
            params.radius?.let { radius ->
                radiusBounds(center.blockX(), center.blockY(), center.blockZ(), radius).appendSql(sql, args, floorCoordinates = true)
            }
            params.chunkRadius?.let { chunkRadius ->
                chunkBounds(center.blockX(), center.blockZ(), chunkRadius).appendSql(sql, args)
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
                    statement.bindAll(args)
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
        writeGate.close()
    }
}
