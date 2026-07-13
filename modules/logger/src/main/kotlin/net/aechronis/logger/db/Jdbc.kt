package net.aechronis.logger.db

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

internal fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

internal fun PreparedStatement.bindAll(values: List<Any>) {
    values.forEachIndexed { index, value ->
        when (value) {
            is Int -> setInt(index + 1, value)
            is Long -> setLong(index + 1, value)
            is Byte -> setByte(index + 1, value)
            is String -> setString(index + 1, value)
            else -> throw IllegalArgumentException("unsupported bind type: ${value::class}")
        }
    }
}

internal fun PreparedStatement.setNullableInt(
    index: Int,
    value: Int?,
) {
    if (value != null) setInt(index, value) else setNull(index, Types.INTEGER)
}

internal fun PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) {
    if (value != null) setString(index, value) else setNull(index, Types.VARCHAR)
}

internal fun PreparedStatement.setNullableBytes(
    index: Int,
    value: ByteArray?,
) {
    if (value != null) setBytes(index, value) else setNull(index, Types.BLOB)
}

internal fun ResultSet.getNullableInt(columnLabel: String): Int? {
    val value = getInt(columnLabel)
    return if (wasNull()) null else value
}
