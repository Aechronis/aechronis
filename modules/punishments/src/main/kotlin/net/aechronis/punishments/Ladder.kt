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

/**
 * One part of an infraction sheet cell: a [type] lasting between [min] and [max]. A mute or ban with a null [min]
 * is permanent; warnings, iteration bans, IP bans and blacklists have no length.
 */
data class Step(
    val type: Type,
    val min: Duration? = null,
    val max: Duration? = min,
) {
    /** Whether staff pick the length, e.g. "1-4h mute". */
    val ranged: Boolean get() = min != null && max != null && max > min

    fun clamp(duration: Duration): Duration = duration.coerceIn(min!!, max!!)

    fun action(duration: Duration? = min): Action = Action(type, if (type == Type.MUTE || type == Type.BAN) duration else null)

    fun describe(): String =
        when {
            ranged -> "${formatDuration(min!!)}-${formatDuration(max!!)} ${type.label.lowercase()}"
            else -> action().describe()
        }
}

/** The infraction sheet's categories. Staff need one of [permissions] to punish in it. */
enum class Category(
    val label: String,
    val permissions: List<String>,
) {
    CHAT("Chat", listOf(PunishPerms.CHAT, PunishPerms.PUNISH)),
    BUILD("Build", listOf(PunishPerms.PUNISH)),
    GENERAL("General", listOf(PunishPerms.PUNISH)),
    WAR("War", listOf(PunishPerms.PUNISH)),
    NATION("Nation", listOf(PunishPerms.PUNISH)),
    MODIFICATIONS("Modifications", listOf(PunishPerms.PUNISH)),
    BAN_EVASION("Ban evasion", listOf(PunishPerms.PUNISH)),
    ADMIN("Admin+", listOf(PunishPerms.BLACKLIST)),
}

enum class Severity(
    val label: String,
) {
    MINOR("Minor"),
    MEDIUM("Medium"),
    MAJOR("Major"),
    BLACKLIST("Blacklist"),
}

private fun hours(amount: Long) = Duration.ofHours(amount)

private fun days(amount: Long) = Duration.ofDays(amount)

private val warn = Step(Type.WARN)
private val iterationBan = Step(Type.ITERATION_BAN)
private val blacklist = Step(Type.BLACKLIST)

private fun mute(
    min: Duration?,
    max: Duration? = min,
) = Step(Type.MUTE, min, max)

private fun ban(
    min: Duration,
    max: Duration = min,
) = Step(Type.BAN, min, max)

/**
 * One row of the infraction sheet. Each strike in the row moves to the next level; strikes past the last level repeat
 * it. Rows that lead to a blacklist never [decays]. [incompletable] rows can be flagged as still needing a nuke or
 * rollback.
 */
enum class Infraction(
    val category: Category,
    val severity: Severity,
    val examples: String,
    val levels: List<List<Step>>,
    val decays: Boolean = true,
    val incompletable: Boolean = false,
) {
    CHAT_MINOR(
        Category.CHAT,
        Severity.MINOR,
        "Spamming chat, minor staff harassment",
        listOf(listOf(warn), listOf(mute(hours(1), hours(4))), listOf(mute(days(1), days(3))), listOf(mute(days(7), days(9)))),
    ),
    CHAT_MEDIUM(
        Category.CHAT,
        Severity.MEDIUM,
        "Low level racism, advertising, inappropriate debates",
        listOf(listOf(mute(hours(1), hours(3))), listOf(mute(days(1))), listOf(mute(days(10), days(12))), listOf(ban(days(2)))),
    ),
    CHAT_MAJOR(
        Category.CHAT,
        Severity.MAJOR,
        "Slur usage, encouraging self harm, doxx threats, harassment, extreme hate speech",
        listOf(
            listOf(mute(days(5), days(7)), ban(hours(1))),
            listOf(mute(days(14), days(18)), ban(days(1))),
            listOf(ban(days(7)), mute(null)),
        ),
    ),
    BUILD_MINOR(
        Category.BUILD,
        Severity.MINOR,
        "Meme builds or walls, nerd poling, block glitching, illegal walls",
        listOf(listOf(ban(days(1))), listOf(ban(days(2), days(4))), listOf(ban(days(6), days(8))), listOf(ban(days(12), days(16)))),
        incompletable = true,
    ),
    BUILD_MEDIUM(
        Category.BUILD,
        Severity.MEDIUM,
        "Instant kill traps, teleport cancelling traps, skybases, excessive griefing",
        listOf(listOf(ban(days(2), days(4))), listOf(ban(days(6), days(8))), listOf(iterationBan)),
        incompletable = true,
    ),
    BUILD_MAJOR(
        Category.BUILD,
        Severity.MAJOR,
        "Lag machines",
        listOf(listOf(iterationBan), listOf(blacklist)),
        decays = false,
        incompletable = true,
    ),
    GENERAL_MINOR(
        Category.GENERAL,
        Severity.MINOR,
        "Safezone abuse, spawn camping off war, combat log bypass",
        listOf(listOf(ban(days(1), days(2))), listOf(ban(days(2), days(4))), listOf(ban(days(6), days(8))), listOf(iterationBan)),
    ),
    GENERAL_MEDIUM(
        Category.GENERAL,
        Severity.MEDIUM,
        "Minor duping, not reporting exploits or rule breaks, lying to staff, abusing donator perms, alting",
        listOf(listOf(ban(days(3), days(5))), listOf(ban(days(6), days(8))), listOf(iterationBan)),
    ),
    GENERAL_MAJOR(
        Category.GENERAL,
        Severity.MAJOR,
        "Major duping, major exploiting, assisting blacklisted players",
        listOf(listOf(iterationBan), listOf(blacklist)),
        decays = false,
    ),
    WAR_MINOR(
        Category.WAR,
        Severity.MINOR,
        "Illegal node flipping, sky capping, boating to break flags, illegal cap placement",
        listOf(
            listOf(ban(Duration.ofMinutes(30), hours(1))),
            listOf(ban(days(2), days(4))),
            listOf(ban(days(6), days(8))),
            listOf(ban(days(12), days(16))),
        ),
    ),
    WAR_MEDIUM(
        Category.WAR,
        Severity.MEDIUM,
        "Fighting on after being capped, sky vehicles",
        listOf(listOf(ban(days(1), days(2))), listOf(ban(days(3), days(7))), listOf(ban(days(10), days(12))), listOf(iterationBan)),
    ),
    WAR_MAJOR(
        Category.WAR,
        Severity.MAJOR,
        "Nation sabotage",
        listOf(listOf(ban(days(4), days(6))), listOf(ban(days(14), days(16))), listOf(iterationBan)),
    ),
    NATION_MINOR(
        Category.NATION,
        Severity.MINOR,
        "Insiding, griefing your own nation",
        listOf(listOf(ban(days(7), days(9))), listOf(iterationBan)),
        incompletable = true,
    ),
    NATION_MAJOR(
        Category.NATION,
        Severity.MAJOR,
        "Promoting illegal action in your own nation, illegal nation action",
        listOf(listOf(iterationBan)),
    ),
    MODIFICATIONS_MINOR(
        Category.MODIFICATIONS,
        Severity.MINOR,
        "Small illegal modifications: mouse tweaks, inventory move, mining macros",
        listOf(listOf(ban(days(3), days(4))), listOf(iterationBan)),
    ),
    MODIFICATIONS_MAJOR(
        Category.MODIFICATIONS,
        Severity.MAJOR,
        "Major cheats (ESP, triggerbot, aimbot), refusing to screenshare",
        listOf(listOf(iterationBan), listOf(blacklist)),
        decays = false,
    ),
    BAN_EVASION_MAJOR(
        Category.BAN_EVASION,
        Severity.MAJOR,
        "Evading a ban (players who are not blacklisted or iteration banned)",
        listOf(listOf(iterationBan, Step(Type.IP_BAN)), listOf(blacklist)),
        decays = false,
    ),
    ADMIN_BLACKLIST(
        Category.ADMIN,
        Severity.BLACKLIST,
        "Doxxing, sexual harassment, grooming, predatory action, IRL trading, repeated rule breaks",
        listOf(listOf(blacklist)),
        decays = false,
    ),
    ;

    val label: String get() = "${category.label} · ${severity.label}"

    /** The level a player with [strikes] earlier strikes gets next. */
    fun level(strikes: Int): List<Step> = levels[strikes.coerceIn(0, levels.lastIndex)]

    companion object {
        fun parse(name: String?): Infraction? = entries.firstOrNull { it.name == name }
    }
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
