package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.config.PunishConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent
import net.minestom.server.event.player.PlayerChatEvent
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class PunishmentAction {
    WARNING,
    MUTE,
    BAN,
    ITERATION_BAN,
    BLACKLIST,
}

data class PunishmentTier(
    val action: PunishmentAction,
    val durationMs: Long? = null,
) {
    init {
        require(
            action == PunishmentAction.WARNING ||
                action == PunishmentAction.ITERATION_BAN ||
                action == PunishmentAction.BLACKLIST ||
                durationMs != null,
        ) {
            "$action requires a duration"
        }
    }
}

data class PunishmentTemplate(
    val id: String,
    val label: String,
    val rule: String,
    val tiers: List<PunishmentTier>,
) {
    init {
        require(tiers.size == 5) { "Punishment template $id must have exactly five tiers" }
    }
}

data class PunishedPlayer(
    val uuid: UUID,
    val name: String,
)

private const val PUNISH_MINUTE = 60_000L
private const val PUNISH_HOUR = 3_600_000L
private const val PUNISH_DAY = 86_400_000L

private fun mute(
    amount: Long,
    unit: Long,
) = PunishmentTier(PunishmentAction.MUTE, amount * unit)

private fun ban(
    amount: Long,
    unit: Long,
) = PunishmentTier(PunishmentAction.BAN, amount * unit)

private fun warning() = PunishmentTier(PunishmentAction.WARNING)

private fun iteration() = PunishmentTier(PunishmentAction.ITERATION_BAN)

private fun blacklist() = PunishmentTier(PunishmentAction.BLACKLIST)

data class PunishmentRecord(
    val id: UUID,
    val targetName: String,
    val staffName: String,
    val reason: String,
    val action: PunishmentAction,
    val issuedAt: Long,
    val expiresAt: Long?,
    val templateId: String?,
    val strike: Int?,
    val revokedAt: Long?,
)

/**
 * Persistent moderation service. Template strikes are scoped to a player and the
 * configured iteration; manually issued mutes and bans remain audit records but
 * never change automatic template strikes.
 */
object Punish {
    private val initialized = AtomicBoolean()
    private lateinit var repository: PunishmentRepository
    private lateinit var config: PunishConfig

    val templates: List<PunishmentTemplate> =
        listOf(
            template("abusing-chat", "Abusing chat", "1.1", Profile.CHAT),
            template("advertising", "Advertising another server", "1.1", Profile.CHAT),
            template("respect-and-conduct", "Harassment, hate speech, or discrimination", "1.2", Profile.CONDUCT),
            template(
                "disruptive-debate-or-tragedy",
                "Disruptive political/religious debate or glorifying illegal acts/tragedies",
                "1.2",
                Profile.CONDUCT,
            ),
            template("failure-to-assist-staff", "Failing to report or concealing a violation", "2.1", Profile.GAMEPLAY),
            template("false-evidence", "False, altered, or misleading evidence", "2.1", Profile.GAMEPLAY),
            template("minor-safety", "Harassment, grooming, or illegal sexual interaction involving a minor", "2.2", Profile.CRITICAL),
            template("privacy-leak", "Sharing or threatening to share private information", "2.2", Profile.CRITICAL),
            template("minecraft-tos", "Minecraft Terms of Service violation", "2.3", Profile.GAMEPLAY),
            template("real-money-trading", "Real-money trading", "2.3", Profile.GAMEPLAY),
            template("exploit-abuse", "Exploiting a bug, glitch, or unintended mechanic", "3.1", Profile.CLIENT),
            template("failure-to-report-exploit", "Failure to report an exploit", "3.1", Profile.GAMEPLAY),
            template("prohibited-items", "Obtaining items through prohibited means", "3.1", Profile.CLIENT),
            template("koth-vehicle", "Using a vehicle in KOTH", "3.1", Profile.BUILD_WAR),
            template("unapproved-alt", "Using an unapproved alternate account", "3.2", Profile.GAMEPLAY),
            template("alt-in-another-nation", "Having an alt in another nation", "3.2", Profile.GAMEPLAY),
            template("spawn-camping", "Spawn camping outside wartime", "3.3", Profile.BUILD_WAR),
            template("building-violation", "Building regulation violation", "4.1", Profile.BUILD_WAR),
            template("excessive-griefing", "Excessive griefing", "4.2", Profile.GAMEPLAY),
            template("nation-rule", "Nation rule violation", "5.1-5.5", Profile.GAMEPLAY),
            template("insiding", "Insiding or storage wiping", "5.2", Profile.CLIENT),
            template("schematic-misuse", "Accessing another player's build or schematic", "6.1", Profile.CLIENT),
            template("unfair-mod", "Using a mod/client with an unfair vanilla advantage", "6.2", Profile.CLIENT),
            template("automation-or-macro", "Using automation or a macro", "6.2", Profile.CLIENT),
            template("esp-xray", "Using ESP, X-ray, or a vision hack", "6.2", Profile.CLIENT),
            template("hacked-client", "Using a hacked client or combat-altering mod", "6.2", Profile.CLIENT),
            template("unvouched-vpn", "Using an unvouched VPN", "6.2", Profile.GAMEPLAY),
            template("artillery-calculator", "Using an artillery/mortar calculator", "6.2", Profile.GAMEPLAY),
            template("screenshare-refusal", "Refusing or dodging a screenshare", "6.3", Profile.SCREEN_SHARE),
            template("hacked-client-screenshare", "Hacked client found during screenshare", "6.3", Profile.SCREEN_SHARE),
            template("illegal-mod-screenshare", "Illegal mod found during screenshare", "6.3", Profile.ILLEGAL_MOD),
            template("discord-rule", "Discord rule violation", "7.1-7.4", Profile.CONDUCT),
            template("warzone-rule", "Warzone rule violation", "8.1-8.4", Profile.BUILD_WAR),
            template("general-war-rule", "General war rule violation", "9.1-9.8", Profile.BUILD_WAR),
            template("war-mechanic", "War mechanic/restriction violation", "10.1-10.7", Profile.BUILD_WAR),
            template("undeclared-war", "Undeclared war", "11.1", Profile.BUILD_WAR),
            template("war-combat-rule", "War combat rule violation", "13.1-13.6", Profile.BUILD_WAR),
        )

    private val templatesById = templates.associateBy(PunishmentTemplate::id)

    fun init(
        path: Path,
        punishmentConfig: PunishConfig,
    ) {
        if (!initialized.compareAndSet(false, true)) return
        config = punishmentConfig
        repository = PunishmentRepository(path)
        Vanilla.eventNode.addListener(AsyncPlayerPreLoginEvent::class.java, ::onPreLogin)
        Vanilla.eventNode.addListener(PlayerChatEvent::class.java, ::onChat)
    }

    fun template(id: String): PunishmentTemplate? = templatesById[id.lowercase()]

    fun templateIdsMatching(prefix: String): List<String> =
        templates
            .asSequence()
            .map(PunishmentTemplate::id)
            .filter { it.startsWith(prefix, true) }
            .sorted()
            .toList()

    fun namesMatching(prefix: String): List<String> = repository.namesMatching(prefix)

    fun resolvePlayer(name: String): PunishedPlayer? {
        MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name)?.let {
            repository.rememberPlayer(it.uuid, it.username)
            return PunishedPlayer(it.uuid, it.username)
        }
        return repository.findPlayer(name)
    }

    fun punish(
        staff: Player,
        targetName: String,
        templateId: String,
    ): Result<PunishmentRecord> {
        val target = resolvePlayer(targetName) ?: return Result.failure(IllegalArgumentException("Unknown player: $targetName"))
        val template = template(templateId) ?: return Result.failure(IllegalArgumentException("Unknown reason: $templateId"))
        val previousStrikes = repository.templateStrikes(target.uuid, template.id, config.iterationId)
        val strike = (previousStrikes + 1).coerceAtMost(template.tiers.size)
        val tier = template.tiers[strike - 1]
        val record = repository.issue(target, staff, template.label, tier, template.id, strike, config.iterationId)
        enforceImmediately(target.uuid, record)
        return Result.success(record)
    }

    fun manual(
        staff: Player,
        targetName: String,
        action: PunishmentAction,
        reason: String,
        durationMs: Long,
    ): Result<PunishmentRecord> {
        require(action == PunishmentAction.MUTE || action == PunishmentAction.BAN) { "Manual action must be mute or ban" }
        val target = resolvePlayer(targetName) ?: return Result.failure(IllegalArgumentException("Unknown player: $targetName"))
        val record = repository.issue(target, staff, reason, PunishmentTier(action, durationMs), null, null, config.iterationId)
        enforceImmediately(target.uuid, record)
        return Result.success(record)
    }

    fun history(
        name: String,
        page: Int,
    ): Pair<PunishedPlayer, List<PunishmentRecord>>? {
        val target = resolvePlayer(name) ?: return null
        return target to repository.history(target.uuid, page)
    }

    fun isMuted(uuid: UUID): Boolean = initialized.get() && repository.active(uuid, PunishmentAction.MUTE) != null

    fun duration(text: String): Long? {
        val matches = Regex("(\\d+)([smhdw])", RegexOption.IGNORE_CASE).findAll(text).toList()
        if (matches.isEmpty() || matches.joinToString("") { it.value }.length != text.length) return null
        return try {
            matches
                .fold(0L) { total, match ->
                    val amount = match.groupValues[1].toLong()
                    val unit =
                        when (match.groupValues[2].lowercase()) {
                            "s" -> 1_000L
                            "m" -> 60_000L
                            "h" -> 3_600_000L
                            "d" -> 86_400_000L
                            else -> 604_800_000L
                        }
                    Math.addExact(total, Math.multiplyExact(amount, unit))
                }.takeIf { it > 0 }
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun revoke(
        staff: Player,
        name: String,
        action: PunishmentAction,
    ): Boolean {
        val target = resolvePlayer(name) ?: return false
        val actions =
            if (action == PunishmentAction.BAN) {
                listOf(PunishmentAction.BAN, PunishmentAction.ITERATION_BAN, PunishmentAction.BLACKLIST)
            } else {
                listOf(action)
            }
        var revoked = false
        for (candidate in actions) {
            revoked = repository.revoke(target.uuid, candidate, staff) || revoked
        }
        return revoked
    }

    private fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        val profile = event.gameProfile
        repository.rememberPlayer(profile.uuid(), profile.name())
        val ban = repository.activeBan(profile.uuid(), config.iterationId) ?: return
        event.connection.kick(Component.text(banMessage(ban), NamedTextColor.RED))
    }

    private fun onChat(event: PlayerChatEvent) {
        if (!isMuted(event.player.uuid)) return
        event.isCancelled = true
        event.player.sendMessage(Component.text("You are currently muted.", NamedTextColor.RED))
    }

    private fun enforceImmediately(
        target: UUID,
        record: PunishmentRecord,
    ) {
        if (record.action != PunishmentAction.BAN &&
            record.action != PunishmentAction.ITERATION_BAN &&
            record.action != PunishmentAction.BLACKLIST
        ) {
            return
        }
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .firstOrNull {
                it.uuid == target
            }?.kick(Component.text(banMessage(record), NamedTextColor.RED))
    }

    private fun banMessage(record: PunishmentRecord): String =
        when (record.action) {
            PunishmentAction.ITERATION_BAN -> "You are banned for the current iteration. Reason: ${record.reason}"
            PunishmentAction.BLACKLIST -> "You are blacklisted. Reason: ${record.reason}"
            else -> "You are banned until ${java.time.Instant.ofEpochMilli(requireNotNull(record.expiresAt))}. Reason: ${record.reason}"
        }

    private enum class Profile(
        val tiers: List<PunishmentTier>,
    ) {
        CHAT(listOf(mute(30, PUNISH_MINUTE), mute(2, PUNISH_HOUR), mute(12, PUNISH_HOUR), ban(1, PUNISH_DAY), ban(7, PUNISH_DAY))),
        CONDUCT(listOf(mute(2, PUNISH_HOUR), mute(1, PUNISH_DAY), ban(3, PUNISH_DAY), ban(7, PUNISH_DAY), iteration())),
        BUILD_WAR(listOf(warning(), ban(1, PUNISH_DAY), ban(3, PUNISH_DAY), ban(7, PUNISH_DAY), iteration())),
        GAMEPLAY(listOf(ban(1, PUNISH_DAY), ban(3, PUNISH_DAY), ban(7, PUNISH_DAY), ban(14, PUNISH_DAY), iteration())),
        CLIENT(listOf(ban(3, PUNISH_DAY), ban(7, PUNISH_DAY), ban(14, PUNISH_DAY), iteration(), iteration())),
        ILLEGAL_MOD(listOf(ban(7, PUNISH_DAY), ban(14, PUNISH_DAY), iteration(), iteration(), iteration())),
        SCREEN_SHARE(listOf(iteration(), blacklist(), blacklist(), blacklist(), blacklist())),
        CRITICAL(listOf(blacklist(), blacklist(), blacklist(), blacklist(), blacklist())),
    }

    private fun template(
        id: String,
        label: String,
        rule: String,
        profile: Profile,
    ) = PunishmentTemplate(id, label, rule, profile.tiers)
}

private class PunishmentRepository(
    databasePath: Path,
) {
    private val url: String

    init {
        databasePath.parent?.let(Files::createDirectories)
        url =
            "jdbc:h2:file:${databasePath.toAbsolutePath().normalize().toString().replace(
                '\\',
                '/',
            )};DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000"
        connect { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS punishment_players (player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(16) NOT NULL, last_seen_at BIGINT NOT NULL)",
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_punishment_players_name ON punishment_players(player_name)")
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS punishments (punishment_id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36) NOT NULL, target_name VARCHAR(16) NOT NULL, staff_uuid VARCHAR(36) NOT NULL, staff_name VARCHAR(16) NOT NULL, template_id VARCHAR(64), strike_number INT, iteration_id VARCHAR(64) NOT NULL, reason VARCHAR(512) NOT NULL, action VARCHAR(32) NOT NULL, issued_at BIGINT NOT NULL, expires_at BIGINT, revoked_at BIGINT, revoked_by_uuid VARCHAR(36), revoked_by_name VARCHAR(16))",
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_punishments_target ON punishments(target_uuid, issued_at DESC)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_punishments_active ON punishments(target_uuid, action, expires_at)")
            }
        }
    }

    fun rememberPlayer(
        uuid: UUID,
        name: String,
    ) {
        connect { connection ->
            connection
                .prepareStatement(
                    "MERGE INTO punishment_players (player_uuid, player_name, last_seen_at) KEY(player_uuid) VALUES (?, ?, ?)",
                ).use {
                    it.setString(1, uuid.toString())
                    it.setString(2, name)
                    it.setLong(3, System.currentTimeMillis())
                    it.executeUpdate()
                }
        }
    }

    fun findPlayer(name: String): PunishedPlayer? =
        connect { connection ->
            connection.prepareStatement("SELECT player_uuid, player_name FROM punishment_players WHERE LOWER(player_name) = LOWER(?)").use {
                it.setString(1, name)
                it.executeQuery().use { rows ->
                    if (rows.next()) PunishedPlayer(UUID.fromString(rows.getString(1)), rows.getString(2)) else null
                }
            }
        }

    fun namesMatching(prefix: String): List<String> =
        connect { connection ->
            connection
                .prepareStatement(
                    "SELECT player_name FROM punishment_players WHERE LOWER(player_name) LIKE ? ORDER BY player_name LIMIT 100",
                ).use {
                    it.setString(1, "${prefix.lowercase()}%")
                    it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
        }

    fun templateStrikes(
        uuid: UUID,
        templateId: String,
        iterationId: String,
    ): Int =
        connect { connection ->
            connection
                .prepareStatement(
                    "SELECT COUNT(*) FROM punishments WHERE target_uuid = ? AND template_id = ? AND iteration_id = ? AND revoked_at IS NULL",
                ).use {
                    it.setString(1, uuid.toString())
                    it.setString(2, templateId)
                    it.setString(3, iterationId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }

    fun issue(
        target: PunishedPlayer,
        staff: Player,
        reason: String,
        tier: PunishmentTier,
        templateId: String?,
        strike: Int?,
        iterationId: String,
    ): PunishmentRecord {
        val now = System.currentTimeMillis()
        val expiry = tier.durationMs?.let { Math.addExact(now, it) }
        val record =
            PunishmentRecord(UUID.randomUUID(), target.name, staff.username, reason, tier.action, now, expiry, templateId, strike, null)
        connect { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO punishments (punishment_id, target_uuid, target_name, staff_uuid, staff_name, template_id, strike_number, iteration_id, reason, action, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use {
                    it.setString(1, record.id.toString())
                    it.setString(2, target.uuid.toString())
                    it.setString(3, target.name)
                    it.setString(4, staff.uuid.toString())
                    it.setString(5, staff.username)
                    it.setString(6, templateId)
                    if (strike ==
                        null
                    ) {
                        it.setNull(7, java.sql.Types.INTEGER)
                    } else {
                        it.setInt(7, strike)
                    }
                    it.setString(8, iterationId)
                    it.setString(9, reason)
                    it.setString(10, tier.action.name)
                    it.setLong(11, now)
                    if (expiry == null) it.setNull(12, java.sql.Types.BIGINT) else it.setLong(12, expiry)
                    it.executeUpdate()
                }
        }
        return record
    }

    fun active(
        uuid: UUID,
        action: PunishmentAction,
    ): PunishmentRecord? = active(uuid, listOf(action))

    fun activeBan(
        uuid: UUID,
        iterationId: String,
    ): PunishmentRecord? =
        connect { connection ->
            connection
                .prepareStatement(
                    "SELECT * FROM punishments WHERE target_uuid = ? AND action IN (?, ?, ?) AND " +
                        "(action <> ? OR iteration_id = ?) AND revoked_at IS NULL AND " +
                        "(expires_at IS NULL OR expires_at > ?) ORDER BY " +
                        "CASE WHEN expires_at IS NULL THEN 1 ELSE 0 END DESC, expires_at DESC LIMIT 1",
                ).use { statement ->
                    statement.setString(1, uuid.toString())
                    statement.setString(2, PunishmentAction.BAN.name)
                    statement.setString(3, PunishmentAction.ITERATION_BAN.name)
                    statement.setString(4, PunishmentAction.BLACKLIST.name)
                    statement.setString(5, PunishmentAction.ITERATION_BAN.name)
                    statement.setString(6, iterationId)
                    statement.setLong(7, System.currentTimeMillis())
                    statement.executeQuery().use { rows -> if (rows.next()) row(rows) else null }
                }
        }

    private fun active(
        uuid: UUID,
        actions: List<PunishmentAction>,
    ): PunishmentRecord? =
        connect { connection ->
            val placeholders = actions.joinToString(",") { "?" }
            connection
                .prepareStatement(
                    "SELECT * FROM punishments WHERE target_uuid = ? AND action IN ($placeholders) AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?) ORDER BY CASE WHEN expires_at IS NULL THEN 1 ELSE 0 END DESC, expires_at DESC LIMIT 1",
                ).use {
                    it.setString(1, uuid.toString())
                    actions.forEachIndexed { index, action -> it.setString(index + 2, action.name) }
                    it.setLong(
                        actions.size + 2,
                        System.currentTimeMillis(),
                    )
                    it.executeQuery().use { rows -> if (rows.next()) row(rows) else null }
                }
        }

    fun history(
        uuid: UUID,
        page: Int,
    ): List<PunishmentRecord> =
        connect { connection ->
            connection.prepareStatement("SELECT * FROM punishments WHERE target_uuid = ? ORDER BY issued_at DESC LIMIT 10 OFFSET ?").use {
                it.setString(1, uuid.toString())
                it.setInt(2, (page - 1).coerceAtLeast(0) * 10)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
            }
        }

    fun revoke(
        uuid: UUID,
        action: PunishmentAction,
        staff: Player,
    ): Boolean =
        connect { connection ->
            connection
                .prepareStatement(
                    "UPDATE punishments SET revoked_at = ?, revoked_by_uuid = ?, revoked_by_name = ? WHERE target_uuid = ? AND action = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)",
                ).use {
                    it.setLong(1, System.currentTimeMillis())
                    it.setString(2, staff.uuid.toString())
                    it.setString(3, staff.username)
                    it.setString(4, uuid.toString())
                    it.setString(5, action.name)
                    it.setLong(6, System.currentTimeMillis())
                    it.executeUpdate() >
                        0
                }
        }

    private fun row(rows: java.sql.ResultSet) =
        PunishmentRecord(
            UUID.fromString(
                rows.getString("punishment_id"),
            ),
            rows.getString(
                "target_name",
            ),
            rows.getString(
                "staff_name",
            ),
            rows.getString(
                "reason",
            ),
            PunishmentAction.valueOf(rows.getString("action")),
            rows.getLong("issued_at"),
            rows.getLong("expires_at").takeUnless {
                rows.wasNull()
            },
            rows.getString("template_id"),
            rows.getInt("strike_number").takeUnless {
                rows.wasNull()
            },
            rows.getLong("revoked_at").takeUnless { rows.wasNull() },
        )

    private fun <T> connect(block: (java.sql.Connection) -> T): T = DriverManager.getConnection(url).use(block)
}
