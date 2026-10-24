package net.aechronis.discord

import dev.kord.common.entity.Snowflake
import java.nio.file.Files
import java.nio.file.Path

/** `/audit`: searches the staff command log written by the vanilla module, so staff can see who did something. */
internal object AuditSearch {
    private val log = Path.of("staff-audit.log")
    private const val MAX_LINES = 300

    private val staffRole =
        System
            .getenv("DISCORD_STAFF_ROLE_ID")
            ?.trim()
            ?.toULongOrNull()
            ?.takeIf { it > 0u }
            ?.let(::Snowflake)

    // TEMPORARY: open to everyone while DISCORD_STAFF_ROLE_ID is unset, for testing. Set it to gate /audit to staff.
    fun allowed(roles: List<Snowflake>) = staffRole == null || staffRole in roles

    fun search(
        query: String,
        hours: Long,
    ): CommandReply {
        if (!Files.exists(log)) return CommandReply("No staff activity has been logged yet.")
        val since = System.currentTimeMillis() - hours * 3_600_000
        val needle = query.trim()
        val hits =
            Files.lines(log).use { lines ->
                lines
                    .map { it.split('\t', limit = 4) }
                    .filter { it.size == 4 && (it[0].toLongOrNull() ?: 0) >= since && it[3].contains(needle, ignoreCase = true) }
                    .toList()
            }
        if (hits.isEmpty()) return CommandReply("Nothing matching \"$needle\" in the last $hours hours.")
        val who =
            hits
                .groupingBy { it[2] }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
        val header =
            "${hits.size} match(es) for \"$needle\" in the last $hours hours. By staff: " +
                who.joinToString(", ") { "${it.key} (${it.value})" }
        val rows =
            hits
                .asReversed()
                .take(MAX_LINES)
                .map { "<t:${it[0].toLong() / 1000}:f> ${it[2]}: ${it[3]}" }
        val more = if (hits.size > MAX_LINES) listOf("…and ${hits.size - MAX_LINES} older matches") else emptyList()
        return CommandReply(listOf(header, "") + rows + more)
    }
}
