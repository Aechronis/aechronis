package net.aechronis.logger.utils

import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

internal fun StringBuilder.appendLogFilters(
    args: MutableList<Any>,
    users: List<String>,
    source: String? = null,
    origin: String? = null,
    since: Long? = null,
    until: Long? = null,
) {
    if (users.isNotEmpty()) {
        append(" AND LOWER(player_name) IN (${placeholders(users.size)})")
        users.forEach { args += it.lowercase() }
    }
    source?.let {
        append(" AND LOWER(source) = ?")
        args += it.lowercase()
    }
    origin?.let {
        append(" AND LOWER(origin) = ?")
        args += it.lowercase()
    }
    since?.let {
        append(" AND ts >= ?")
        args += it
    }
    until?.let {
        append(" AND ts <= ?")
        args += it
    }
}

internal data class BlockBounds(
    val x: IntRange,
    val y: IntRange?,
    val z: IntRange,
) {
    fun contains(
        x: Int,
        y: Int,
        z: Int,
    ): Boolean = x in this.x && (this.y == null || y in this.y) && z in this.z

    fun appendSql(
        sql: StringBuilder,
        args: MutableList<Any>,
        floorCoordinates: Boolean = false,
        requireCoordinates: Boolean = false,
    ) {
        val axes = listOfNotNull("x" to x, y?.let { "y" to it }, "z" to z)
        if (requireCoordinates) {
            axes.forEach { (axis, _) -> sql.append(" AND $axis IS NOT NULL") }
        }
        axes.forEach { (axis, bounds) ->
            val column = if (floorCoordinates) "FLOOR($axis)" else axis
            sql.append(" AND $column BETWEEN ? AND ?")
            args += bounds.first
            args += bounds.last
        }
    }
}

internal fun radiusBounds(
    centerX: Int,
    centerY: Int,
    centerZ: Int,
    radius: Int,
): BlockBounds = BlockBounds(centerX - radius..centerX + radius, centerY - radius..centerY + radius, centerZ - radius..centerZ + radius)

internal fun chunkBounds(
    centerX: Int,
    centerZ: Int,
    chunkRadius: Int,
): BlockBounds {
    val expand = chunkRadius - 1
    return BlockBounds(
        (((centerX shr 4) - expand) shl 4)..((((centerX shr 4) + expand) shl 4) + 15),
        null,
        (((centerZ shr 4) - expand) shl 4)..((((centerZ shr 4) + expand) shl 4) + 15),
    )
}

/** Keeps a stable database snapshot while decoded item or storage-location filters skip rows. */
internal fun <T> DataSource.queryFilteredPages(
    sql: String,
    args: List<Any>,
    limit: Int,
    mapRow: (ResultSet) -> T,
    matches: (T) -> Boolean,
): List<T> {
    val pageSize = minOf(limit, 512)
    val rows = mutableListOf<T>()
    var offset = 0
    connection.use { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.autoCommit = false
        while (rows.size < limit) {
            var fetched = 0
            connection.prepareStatement("$sql LIMIT ? OFFSET ?").use { statement ->
                statement.bindAll(args + pageSize + offset)
                statement.executeQuery().use { results ->
                    while (results.next()) {
                        fetched++
                        val row = mapRow(results)
                        if (matches(row)) {
                            rows += row
                            if (rows.size == limit) break
                        }
                    }
                }
            }
            if (fetched < pageSize) break
            offset += fetched
        }
        connection.commit()
    }
    return rows
}
