package net.aechronis.punishments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    val category: Category?,
    val reason: String,
    val staffUuid: UUID?,
    val staffName: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val revokedAt: Long? = null,
    val revokedBy: String? = null,
    val revokeReason: String? = null,
    /** Staff-only: picked chat lines and clip links, one per line. Never sent to the website. */
    val evidence: String? = null,
) {
    fun active(now: Long = System.currentTimeMillis()): Boolean =
        type != Type.WARN && revokedAt == null && (expiresAt == null || expiresAt > now)
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

internal data class KnownPlayer(
    val uuid: UUID,
    val name: String,
    val lastIp: String?,
)

/** Public entry point for other modules (the discord module hooks in here). */
object Punishments {
    /** Called after every new record, and again with revoked fields set when a record is lifted. */
    val listeners = CopyOnWriteArrayList<(Record) -> Unit>()

    /** Called with an offence's records after staff attach more evidence to it. */
    val evidenceListeners = CopyOnWriteArrayList<(List<Record>) -> Unit>()

    /** Set by the discord module while its bot is running. */
    @Volatile
    var discordLookup: ((UUID) -> DiscordInfo?)? = null

    internal lateinit var repository: PunishmentRepository

    /** Commands that work without a connected player, so the discord module can expose them. */
    val commandNames =
        setOf("punish", "warn", "mute", "unmute", "ban", "unban", "ipban", "history", "blacklist", "liftiterationbans", "evidence")

    /** While set on the tick thread, punishment commands add their async work here so the caller can wait for the replies. */
    val commandTasks = ThreadLocal<MutableList<CompletableFuture<*>>>()

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val NAME = Regex("[A-Za-z0-9_]{1,16}")
    private val LINK = Regex("""https?://\S+""")

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

    /** Earlier offences in [category] for the player named [query], or 0 if they never joined. Blocking. */
    fun offences(
        query: String,
        category: Category,
    ): Int = repository.findPlayer(query)?.let { repository.offences(it.uuid, category) } ?: 0

    /** The guideline's next step after [prior] offences, e.g. "1h mute". */
    fun recommendation(
        category: Category,
        prior: Int,
    ): String {
        val step = category.next(prior).joinToString(" + ") { it.describe() }
        val admitted = if (category == Category.ILLEGAL_CLIENT && prior == 0) " (2w ban if they admitted before the Ocean scan)" else ""
        return step + admitted
    }

    internal fun apply(
        target: KnownPlayer,
        actions: List<Action>,
        category: Category?,
        reason: String,
        staff: Staff,
    ): List<Record> {
        val now = System.currentTimeMillis()
        val offence = UUID.randomUUID().toString()
        // Links in the reason, such as Medal clips, are evidence rather than part of the public reason.
        val links = LINK.findAll(reason).map { it.value }.toList()
        val text =
            reason
                .replace(LINK, "")
                .replace(Regex(" {2,}"), " ")
                .trim()
                .ifEmpty { category?.label ?: actions.first().describe() }
        val drafts =
            actions.map { action ->
                Record(
                    id = 0,
                    offence = offence,
                    uuid = target.uuid,
                    name = target.name,
                    type = action.type,
                    category = category,
                    reason = text,
                    staffUuid = staff.uuid,
                    staffName = staff.name,
                    createdAt = now,
                    expiresAt = action.duration?.takeIf { !it.isZero }?.let { now + it.toMillis() },
                    evidence = links.joinToString("\n").ifEmpty { null },
                )
            }
        repository.know(target.uuid, target.name)
        val records = repository.insert(drafts, target.lastIp)
        Enforcement.issued(records, target.lastIp)
        records.forEach(::notifyListeners)
        return records
    }

    /** Lifts every active record of [types] for [target]. */
    internal fun lift(
        target: KnownPlayer,
        types: Set<Type>,
        staff: Staff,
        reason: String,
    ): List<Record> = lifted(repository.revoke(target.uuid, types, staff.name, reason))

    internal fun liftIterationBans(
        staff: Staff,
        reason: String,
    ): List<Record> = lifted(repository.revokeIterationBans(staff.name, reason))

    /** Lifts one record by id, e.g. from the Discord log's Lift button. */
    fun liftRecord(
        id: Long,
        staff: Staff,
        reason: String,
    ): List<Record> = lifted(repository.revokeRecord(id, staff.name, reason))

    /** Adds evidence, such as clip links, to the offence of record [id]. Returns its records, or none if it doesn't exist. */
    fun addEvidence(
        id: Long,
        text: String,
    ): List<Record> = repository.record(id)?.let { addEvidence(it.offence, text) }.orEmpty()

    /** Player names for autocomplete. Blocking. */
    fun names(prefix: String): List<String> = repository.names(prefix, 25)

    internal fun addEvidence(
        offence: String,
        text: String,
    ): List<Record> {
        val records = repository.addEvidence(offence, text)
        evidenceListeners.forEach { listener ->
            try {
                listener(records)
            } catch (error: Exception) {
                System.err.println("[Punishments] Evidence listener failed (${error.javaClass.simpleName})")
            }
        }
        return records
    }

    private fun lifted(records: List<Record>): List<Record> {
        records.map { it.uuid }.distinct().forEach(Enforcement::refreshMute)
        records.forEach(::notifyListeners)
        return records
    }

    private fun notifyListeners(record: Record) {
        listeners.forEach { listener ->
            try {
                listener(record)
            } catch (error: Exception) {
                System.err.println("[Punishments] Listener failed (${error.javaClass.simpleName})")
            }
        }
    }
}
