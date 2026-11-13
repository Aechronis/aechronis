package net.aechronis.punishments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** One stored punishment. Lifting keeps the row and fills the revoked fields. */
data class Record(
    val id: Long,
    /** Shared by every record created for the same offence, e.g. a ban plus a mute. */
    val offence: String,
    val uuid: UUID,
    val name: String,
    val type: Type,
    /** The infraction sheet row; null for records from before the sheet, or not tied to a row. */
    val infraction: Infraction?,
    /** The label of the old category, for records from before the infraction sheet. */
    val legacyCategory: String? = null,
    val reason: String,
    val staffUuid: UUID?,
    val staffName: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val revokedAt: Long? = null,
    val revokedBy: String? = null,
    val revokeReason: String? = null,
    /** Staff-only: the #evidence link, picked chat lines and clip links, one per line. Never sent to the website. */
    val evidence: String? = null,
    /** Which strike in the row this was, starting at 1. */
    val strike: Int? = null,
    /** Hides the staff name from the player and the public; staff still see it. */
    val anonymous: Boolean = false,
    /** Not announced in chat. */
    val silent: Boolean = false,
    /** Still needs a nuke or rollback. */
    val incomplete: Boolean = false,
    /** The Discord staff log post for this offence. */
    val discordMessage: Long? = null,
) {
    fun active(now: Long = System.currentTimeMillis()): Boolean =
        type != Type.WARN && revokedAt == null && (expiresAt == null || expiresAt > now)

    /** The infraction sheet row's label, or the old category's. */
    val categoryLabel: String? get() = infraction?.label ?: legacyCategory

    /** The staff name the player and the public see. */
    val publicStaff: String get() = if (anonymous) ANONYMOUS_STAFF else staffName

    companion object {
        const val ANONYMOUS_STAFF = "Staff"
    }
}

data class DiscordInfo(
    val id: String,
    val username: String?,
)

data class Staff(
    val uuid: UUID?,
    val name: String,
) {
    companion object {
        val CONSOLE = Staff(null, "Console")
    }
}

/** A staff log entry that isn't a punishment, such as a strike added by hand. */
data class Notice(
    val title: String,
    val fields: List<Pair<String, String>>,
    /** Strikes added, removed, or neither (e.g. a new iteration). */
    val change: Int = 0,
)

/** What staff picked in the `/punish` menu besides the lengths. */
data class PunishOptions(
    val evidence: String,
    val reason: String? = null,
    val anonymous: Boolean = false,
    val silent: Boolean = false,
    val incomplete: Boolean = false,
)

internal data class KnownPlayer(
    val uuid: UUID,
    val name: String,
    val lastIp: String?,
)

/** Public entry point for other modules (the discord module hooks in here). */
object Punishments {
    /** Called with all records of an offence whenever it is created or changes (lift, evidence, nuke done). */
    val listeners = CopyOnWriteArrayList<(List<Record>) -> Unit>()

    /** Called for staff log entries that aren't tied to one offence. */
    val noticeListeners = CopyOnWriteArrayList<(Notice) -> Unit>()

    /** Set by the discord module while its bot is running. */
    @Volatile
    var discordLookup: ((UUID) -> DiscordInfo?)? = null

    internal lateinit var repository: PunishmentRepository

    /** Commands that work without a connected player, so the discord module can expose them. */
    val commandNames = setOf("history", "addstrike", "removestrike", "alts", "blacklist", "newiter", "evidence")

    /** While set on the tick thread, punishment commands add their async work here so the caller can wait for the replies. */
    val commandTasks = ThreadLocal<MutableList<CompletableFuture<*>>>()

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val NAME = Regex("[A-Za-z0-9_]{1,16}")

    internal fun discord(uuid: UUID): DiscordInfo? =
        try {
            discordLookup?.invoke(uuid)
        } catch (error: Exception) {
            System.err.println("[Punishments] Discord lookup failed (${error.javaClass.simpleName})")
            null
        }

    /** Online player, then players who joined before, then Mojang for never-seen accounts. Blocking. */
    internal fun resolve(query: String): KnownPlayer? {
        MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull { it.username.equals(query, true) }?.let {
            return repository.findPlayer(it.uuid.toString()) ?: KnownPlayer(it.uuid, it.username, null)
        }
        repository.findPlayer(query)?.let { return it }
        val uuid = runCatching { UUID.fromString(query) }.getOrNull()
        val url =
            when {
                uuid != null -> "https://sessionserver.mojang.com/session/minecraft/profile/${uuid.toString().replace("-", "")}"
                NAME.matches(query) -> "https://api.mojang.com/users/profiles/minecraft/$query"
                else -> return null
            }
        return try {
            val response =
                http.send(
                    HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            if (response.statusCode() != 200) return null
            val json = Json.parseToJsonElement(response.body()).jsonObject
            val id = json["id"]!!.jsonPrimitive.content
            val name = json["name"]!!.jsonPrimitive.content
            val parsed = UUID.fromString(id.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
            KnownPlayer(parsed, name, null)
        } catch (error: Exception) {
            System.err.println("[Punishments] Mojang lookup failed (${error.javaClass.simpleName})")
            null
        }
    }

    /** The record with [id], or null. Blocking. */
    fun record(id: Long): Record? = repository.record(id)

    /** Every record of [offence]. Blocking. */
    fun offence(offence: String): List<Record> = repository.offence(offence)

    /** Strikes per infraction row. Blocking. */
    fun strikes(uuid: UUID): Map<Infraction, Int> = repository.strikes(uuid)

    /** Player names for autocomplete. Blocking. */
    fun names(prefix: String): List<String> = repository.names(prefix, 25)

    /** Saves the remembered Discord post of [offence], so later edits survive a restart. */
    fun rememberDiscordMessage(
        offence: String,
        message: Long,
    ) = repository.setDiscordMessage(offence, message)

    /**
     * Applies the next strike in [infraction] with the [actions] staff picked, adds the strike and announces it
     * unless silent. Returns nothing when the row no longer has [expectedStrikes], since the actions were picked
     * for that level. Serialized so two staff can't both apply the same strike. Blocking.
     */
    @Synchronized
    internal fun punish(
        target: KnownPlayer,
        infraction: Infraction,
        actions: List<Action>,
        options: PunishOptions,
        staff: Staff,
        expectedStrikes: Int,
    ): List<Record> {
        val current = repository.strikes(target.uuid)[infraction] ?: 0
        if (current != expectedStrikes) return emptyList()
        val strike = current + 1
        // An IP ban needs an IP; a never-joined ban evader just gets the iteration ban.
        val applied = actions.filter { it.type != Type.IP_BAN || target.lastIp != null }
        val records = apply(target, applied, infraction, options, staff, strike)
        repository.addStrikes(target.uuid, infraction, 1)
        if (!options.silent) broadcast(target.name, applied, infraction, records.first().publicStaff)
        return records
    }

    private fun apply(
        target: KnownPlayer,
        actions: List<Action>,
        infraction: Infraction,
        options: PunishOptions,
        staff: Staff,
        strike: Int,
    ): List<Record> {
        val now = System.currentTimeMillis()
        val offence = UUID.randomUUID().toString()
        val active = repository.active(target.uuid)
        val drafts =
            actions.map { action ->
                Record(
                    id = 0,
                    offence = offence,
                    uuid = target.uuid,
                    name = target.name,
                    type = action.type,
                    infraction = infraction,
                    reason = options.reason?.trim()?.ifEmpty { null } ?: infraction.label,
                    staffUuid = staff.uuid,
                    staffName = staff.name,
                    createdAt = now,
                    expiresAt = action.duration?.let { stackedExpiry(active, action.type, now) + it.toMillis() },
                    evidence = options.evidence,
                    strike = strike,
                    anonymous = options.anonymous,
                    silent = options.silent,
                    incomplete = options.incomplete && infraction.incompletable,
                )
            }
        repository.know(target.uuid, target.name)
        val records = repository.insert(drafts, target.lastIp)
        Enforcement.issued(records, target.lastIp)
        notifyListeners(records)
        return records
    }

    /** Removes the newest unlifted punishment in [infraction], if any, and one strike. Blocking. */
    internal fun removeStrike(
        target: KnownPlayer,
        infraction: Infraction,
        staff: Staff,
        reason: String,
    ): Pair<List<Record>, Int> {
        val lifted = repository.latestOffence(target.uuid, infraction)?.let { lift(it, staff, reason) }.orEmpty()
        val strikes = repository.addStrikes(target.uuid, infraction, -1)
        if (lifted.isEmpty()) notice("Strike removed", target.name, infraction, strikes, staff, reason, -1)
        return lifted to strikes
    }

    /** Undoes the offence of record [id] and removes its strike, e.g. from the Discord log. Blocking. */
    fun removeStrike(
        id: Long,
        staff: Staff,
        reason: String,
    ): List<Record> {
        val record = repository.record(id) ?: return emptyList()
        val lifted = lift(record.offence, staff, reason)
        if (lifted.isNotEmpty()) record.infraction?.let { repository.addStrikes(record.uuid, it, -1) }
        return lifted
    }

    private fun lift(
        offence: String,
        staff: Staff,
        reason: String,
    ): List<Record> {
        val lifted = repository.revokeOffence(offence, staff.name, reason)
        lifted.map { it.uuid }.distinct().forEach(Enforcement::refreshMute)
        if (lifted.isNotEmpty()) notifyListeners(repository.offence(offence))
        return lifted
    }

    /** `/addstrike` and `/removestrike`: changes the count without punishing or lifting anything. Blocking. */
    internal fun adjustStrikes(
        target: KnownPlayer,
        infraction: Infraction,
        delta: Int,
        staff: Staff,
        reason: String,
    ): Int {
        val strikes = repository.addStrikes(target.uuid, infraction, delta)
        notice(if (delta > 0) "Strike added" else "Strike removed", target.name, infraction, strikes, staff, reason, delta)
        return strikes
    }

    /** Lifts iteration bans (and the IP bans issued with them) and decays every strike row that decays. Blocking. */
    internal fun newIteration(staff: Staff): Pair<List<Record>, Int> {
        val lifted = repository.revokeIterationBans(staff.name, "New iteration")
        lifted.groupBy { it.offence }.keys.forEach { notifyListeners(repository.offence(it)) }
        val decayed = repository.decayStrikes(Infraction.entries.filter { it.decays })
        val ipBans = lifted.count { it.type == Type.IP_BAN }
        notify(
            Notice(
                "New iteration",
                listOf(
                    "Iteration bans lifted" to "${lifted.size - ipBans}",
                    "IP bans lifted" to "$ipBans",
                    "Strike rows decayed" to "$decayed",
                    "Staff" to staff.name,
                ),
            ),
        )
        return lifted to decayed
    }

    /** Marks the nuke or rollback of record [id]'s offence as done. Returns its records, or none if nothing changed. */
    fun markDone(id: Long): List<Record> {
        val record = repository.record(id) ?: return emptyList()
        if (!repository.clearIncomplete(record.offence)) return emptyList()
        return repository.offence(record.offence).also(::notifyListeners)
    }

    /** Adds evidence, such as clip links, to the offence of record [id]. Returns its records, or none if it doesn't exist. */
    fun addEvidence(
        id: Long,
        text: String,
    ): List<Record> = repository.record(id)?.let { addEvidence(it.offence, text) }.orEmpty()

    internal fun addEvidence(
        offence: String,
        text: String,
    ): List<Record> = repository.addEvidence(offence, text).also(::notifyListeners)

    private fun broadcast(
        name: String,
        actions: List<Action>,
        infraction: Infraction,
        staff: String,
    ) {
        val what = actions.mapNotNull(::verb).joinToString(" and ")
        val message = Component.text("$name was $what (${infraction.label}) by $staff.", NamedTextColor.RED)
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.sendMessage(message) }
    }

    private fun verb(action: Action): String? =
        when (action.type) {
            Type.WARN -> "warned"
            Type.MUTE -> action.duration?.let { "muted for ${formatDuration(it)}" } ?: "permanently muted"
            Type.BAN -> action.duration?.let { "banned for ${formatDuration(it)}" } ?: "permanently banned"
            Type.ITERATION_BAN -> "banned for the iteration"
            Type.BLACKLIST -> "blacklisted"
            Type.IP_BAN -> null
        }

    private fun notice(
        title: String,
        player: String,
        infraction: Infraction,
        strikes: Int,
        staff: Staff,
        reason: String,
        change: Int,
    ) = notify(
        Notice(
            "$title · ${infraction.label}",
            listOf(
                "Player" to player,
                "Strikes" to "$strikes/${infraction.levels.size}",
                "Staff" to staff.name,
                "Reason" to reason,
            ),
            change,
        ),
    )

    private fun notify(notice: Notice) =
        noticeListeners.forEach { listener ->
            try {
                listener(notice)
            } catch (error: Exception) {
                System.err.println("[Punishments] Notice listener failed (${error.javaClass.simpleName})")
            }
        }

    private fun notifyListeners(records: List<Record>) {
        if (records.isEmpty()) return
        listeners.forEach { listener ->
            try {
                listener(records)
            } catch (error: Exception) {
                System.err.println("[Punishments] Listener failed (${error.javaClass.simpleName})")
            }
        }
    }
}

/**
 * When a new mute or ban of [type] starts. Two timed punishments of one type stack: the new one starts when the
 * latest active one ends, so their lengths add up.
 */
internal fun stackedExpiry(
    active: List<Record>,
    type: Type,
    now: Long,
): Long = active.filter { it.type == type && it.active(now) }.mapNotNull { it.expiresAt }.fold(now, ::maxOf)
