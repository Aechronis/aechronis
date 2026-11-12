package net.aechronis.punishments

import java.time.Duration

enum class Type(
    val label: String,
) {
    WARN("Warning"),
    MUTE("Mute"),
    BAN("Ban"),
    ITERATION_BAN("Iteration ban"),
    IP_BAN("IP ban"),
    BLACKLIST("Blacklist"),
    ;

    val blocksLogin: Boolean
        get() = this == BAN || this == ITERATION_BAN || this == IP_BAN || this == BLACKLIST
}

/** One punishment to apply. A null [duration] lasts until staff lift it. */
data class Action(
    val type: Type,
    val duration: Duration? = null,
) {
    fun describe(): String =
        when (type) {
            Type.MUTE, Type.BAN -> "${duration?.let(::formatDuration) ?: "Permanent"} ${type.label.lowercase()}"
            else -> type.label
        }
}

private val warn = Action(Type.WARN)
private val blacklist = Action(Type.BLACKLIST)
private val iterationBan = Action(Type.ITERATION_BAN)

private fun mute(duration: Duration?) = Action(Type.MUTE, duration)

private fun ban(duration: Duration) = Action(Type.BAN, duration)

/** The staff punishment guideline. Offences past the last step repeat the last step. */
enum class Category(
    val label: String,
    val examples: String,
    val steps: List<List<Action>>,
) {
    MINOR_CHAT(
        "Minor chat violation",
        "Spamming, low-level racism",
        listOf(listOf(warn), listOf(mute(Duration.ofHours(1))), listOf(mute(Duration.ofDays(1)))),
    ),
    MAJOR_CHAT(
        "Major chat violation",
        "Extreme racism, encouraging self-harm, doxx threats",
        listOf(listOf(mute(Duration.ofDays(14))), listOf(ban(Duration.ofDays(7)), mute(null))),
    ),
    MINOR_EXPLOIT(
        "Minor exploit",
        "Block glitching, sky capping, hitting through doors or walls",
        listOf(listOf(warn), listOf(ban(Duration.ofDays(1))), listOf(ban(Duration.ofDays(7)))),
    ),
    MAJOR_EXPLOIT(
        "Major exploit",
        "Duping, lag machines, anything that directly affects the server or its players",
        listOf(listOf(blacklist)),
    ),
    ILLEGAL_CLIENT(
        "Illegal client or modification",
        "Illegal modifications found in an Ocean scan, or refusing to screen share",
        listOf(listOf(iterationBan), listOf(blacklist)),
    ),
    BAN_EVASION(
        "Ban evasion",
        "Evading a ban (players who are not blacklisted or iteration banned)",
        listOf(listOf(iterationBan, Action(Type.IP_BAN)), listOf(blacklist)),
    ),
    ;

    /**
     * Actions for the offence after [prior] earlier offences in this category. Admitting to
     * cheating before the Ocean scan reduces a first illegal-client offence to a two-week ban.
     */
    fun next(
        prior: Int,
        admitted: Boolean = false,
    ): List<Action> {
        if (this == ILLEGAL_CLIENT && admitted && prior == 0) return listOf(ADMITTED_BAN)
        return steps[prior.coerceIn(0, steps.lastIndex)]
    }

    companion object {
        val ADMITTED_BAN = ban(Duration.ofDays(14))
    }
}

/** What staff pick from in `/punish`. */
val PUNISHMENT_TYPES = listOf("warn", "mute", "ban", "blacklist")
val PUNISHMENT_DURATIONS = listOf("1h", "1d", "3d", "1w", "2w", "iteration", "perm")

/** The `/punish` type and length that apply this action, e.g. `ban` and `1w`; null for IP bans, which have their own command. */
fun Action.choice(): Pair<String, String?>? =
    when (type) {
        Type.WARN -> "warn" to null
        Type.BLACKLIST -> "blacklist" to null
        Type.ITERATION_BAN -> "ban" to "iteration"
        Type.IP_BAN -> null
        Type.MUTE, Type.BAN -> type.name.lowercase() to (duration?.let(::durationToken) ?: "perm")
    }

private fun durationToken(duration: Duration): String =
    when (duration) {
        Duration.ofDays(7) -> "1w"
        Duration.ofDays(14) -> "2w"
        else -> formatDuration(duration).replace(" ", "")
    }

fun formatDuration(duration: Duration): String {
    var seconds = duration.seconds
    if (seconds <= 0) return "0m"
    return buildList {
        listOf('d' to 86_400L, 'h' to 3_600L, 'm' to 60L).forEach { (unit, size) ->
            if (seconds >= size) {
                add("${seconds / size}$unit")
                seconds %= size
            }
        }
        if (isEmpty()) add("${seconds}s")
    }.joinToString(" ")
}

private val DURATION_PART = Regex("(\\d+)([smhdw])")

/** Parses `30m`, `1d12h`, `2w`; `perm`/`permanent` returns [Duration.ZERO] as "no expiry". */
fun parseDuration(input: String): Duration? {
    val text = input.lowercase()
    if (text == "perm" || text == "permanent") return Duration.ZERO
    if (text.isEmpty() || DURATION_PART.replace(text, "").isNotEmpty()) return null
    return DURATION_PART
        .findAll(text)
        .fold(Duration.ZERO) { total, match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val unit =
                when (match.groupValues[2]) {
                    "s" -> Duration.ofSeconds(amount)
                    "m" -> Duration.ofMinutes(amount)
                    "h" -> Duration.ofHours(amount)
                    "d" -> Duration.ofDays(amount)
                    else -> Duration.ofDays(amount * 7)
                }
            total + unit
        }.takeIf { !it.isZero }
}
