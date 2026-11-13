package net.aechronis.discord

import dev.kord.common.Color
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.TextInputStyle
import dev.kord.core.Kord
import dev.kord.core.behavior.MessageBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ModalSubmitInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.allowedMentions
import dev.kord.rest.builder.message.embed
import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.aechronis.punishments.Action
import net.aechronis.punishments.DiscordInfo
import net.aechronis.punishments.Notice
import net.aechronis.punishments.PunishPerms
import net.aechronis.punishments.Punishments
import net.aechronis.punishments.Record
import net.aechronis.punishments.Staff
import net.aechronis.punishments.Type
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives the punishments website linked Discord accounts. With `DISCORD_PUNISHMENT_LOG_CHANNEL_ID` set, the bot
 * posts every offence to that staff channel as one message and edits it as the offence changes: lifted, more
 * evidence, nuke or rollback done. The message id is stored with the offence, so edits survive restarts. Its
 * buttons remove the strike, mark a nuke done or add clip links, for linked staff whose Minecraft account has the
 * matching permission. Close it when the bot stops.
 */
internal class PunishmentBridge(
    private val links: AccountLinks,
    private val kord: Kord,
    private val scope: CoroutineScope,
    private val channel: Snowflake?,
    /** Whether the Minecraft account has the LuckPerms node, loading it if offline. */
    private val allowed: suspend (UUID, String) -> Boolean,
) : AutoCloseable {
    private val usernames = ConcurrentHashMap<String, String>()
    private val lookup: (UUID) -> DiscordInfo? = { uuid -> links.discord(uuid)?.let { DiscordInfo(it, username(it)) } }
    private val listener: (List<Record>) -> Unit = { records -> scope.launch { guarded("log") { sync(records.first().offence) } } }
    private val noticeListener: (Notice) -> Unit = { notice -> scope.launch { guarded("notice") { post(notice) } } }
    private val requests = InteractionRequests()

    /** One sync at a time, so two quick changes to a new offence can't both create a post. */
    private val syncing = Mutex()
    private val handlers = mutableListOf<Job>()

    init {
        Punishments.discordLookup = lookup
        if (channel != null) {
            Punishments.listeners += listener
            Punishments.noticeListeners += noticeListener
        }
        handlers += kord.on<ButtonInteractionCreateEvent>(scope = scope) { onButton(this) }
        handlers += kord.on<ModalSubmitInteractionCreateEvent>(scope = scope) { onModal(this) }
    }

    /** Blocking; called from the website's request threads, never the tick thread. */
    private fun username(id: String): String? =
        usernames[id] ?: runBlocking {
            withTimeoutOrNull(3_000) { kord.getUser(Snowflake(id))?.username }
        }?.also { usernames[id] = it }

    /** Creates or edits the offence's log post from its current records. */
    private suspend fun sync(offence: String) =
        syncing.withLock {
            val log = channel ?: return@withLock
            val records = Punishments.offence(offence).ifEmpty { return@withLock }
            val existing = records.firstNotNullOfOrNull { it.discordMessage }
            if (existing != null) {
                try {
                    MessageBehavior(log, Snowflake(existing), kord).edit {
                        embeds = mutableListOf()
                        embed { describe(records) }
                        components = mutableListOf()
                        buttons(records)
                    }
                    return@withLock
                } catch (error: RestRequestException) {
                    // Deleted by hand: post it again below.
                    if (error.status.code != 404) throw error
                }
            }
            val message =
                MessageChannelBehavior(log, kord).createMessage {
                    allowedMentions { }
                    embed { describe(records) }
                    buttons(records)
                }
            Punishments.rememberDiscordMessage(offence, message.id.value.toLong())
        }

    private suspend fun post(notice: Notice) {
        val log = channel ?: return
        MessageChannelBehavior(log, kord).createMessage {
            allowedMentions { }
            // Laid out like the offence posts: short facts side by side, staff and reason on their own lines.
            embed {
                title = notice.title
                color =
                    Color(
                        when {
                            notice.change > 0 -> 0xF0B232
                            notice.change < 0 -> 0x57F287
                            else -> 0x5865F2
                        },
                    )
                notice.fields.forEach { (name, value) -> field(name, name !in OWN_LINE) { value.ifBlank { "-" }.take(1024) } }
            }
        }
    }

    private fun MessageBuilder.buttons(records: List<Record>) {
        val id = records.first().id
        actionRow {
            // Warnings never count as active, but their strike can still be removed.
            if (records.any { it.revokedAt == null && (it.active() || it.type == Type.WARN) }) {
                interactionButton(ButtonStyle.Danger, "$REMOVE$id") { label = "Remove strike" }
            }
            if (records.any { it.incomplete }) interactionButton(ButtonStyle.Success, "$DONE$id") { label = "Mark nuke/rollback done" }
            interactionButton(ButtonStyle.Secondary, "$ADD_EVIDENCE$id") { label = "Add evidence" }
        }
    }

    private fun EmbedBuilder.describe(records: List<Record>) {
        val first = records.first()
        // Records that expired before the lift stay unrevoked, so lifted means nothing is left to lift.
        val lifted = records.any { it.revokedAt != null } && records.none { it.revokedAt == null && (it.active() || it.type == Type.WARN) }
        val row = first.infraction
        val heading = row?.let { "${it.label} · Strike ${first.strike ?: "?"}/${it.levels.size}" } ?: first.type.label
        title = if (lifted) "Lifted: $heading" else heading
        color =
            Color(
                when {
                    lifted -> 0x57F287
                    first.incomplete -> 0xE67E22
                    records.all { it.type == Type.WARN } -> 0xF0B232
                    else -> 0xED4245
                },
            )
        field("Player", true) { "${first.name}\n`${first.uuid}`" }
        links.discord(first.uuid)?.let { field("Discord", true) { "<@$it>" } }
        field("Punishment", true) {
            records.joinToString("\n") { record ->
                val until = if (record.active() && record.expiresAt != null) " (ends <t:${record.expiresAt!! / 1000}:R>)" else ""
                Action(record.type, record.length).describe() + until
            }
        }
        field("Reason") { first.reason.ifBlank { "-" }.take(1024) }
        field("Staff", true) { first.staffName + if (first.anonymous) " (anonymous)" else "" }
        if (first.silent) field("Announced", true) { "No (silent)" }
        if (first.incomplete) field("Status") { "⚠ Nuke/rollback needed" }
        first.evidence?.let { field("Evidence") { it.take(1024) } }
        first.revokedBy?.let { field("Lifted by", true) { it } }
        first.revokeReason?.let { field("Unban message") { it.take(1024) } }
        footer { text = "Record #${first.id}" }
    }

    /** Why the Discord user can't act on record [id], or null when their linked account may punish in its category. */
    private suspend fun refusal(
        user: Snowflake,
        id: Long,
    ): String? {
        val uuid = links.player(user.toString()) ?: return "Link your Minecraft account first: /discord link in game."
        val record = Punishments.record(id) ?: return "Record #$id doesn't exist."
        // Records from before the infraction sheet: blacklists stay admin-only.
        val nodes =
            record.infraction?.category?.permissions
                ?: listOf(if (record.type == Type.BLACKLIST) PunishPerms.BLACKLIST else PunishPerms.PUNISH)
        return if (nodes.any { allowed(uuid, it) }) null else "You don't have permission to do that."
    }

    private suspend fun onButton(event: ButtonInteractionCreateEvent) {
        val interaction = event.interaction
        val action = interaction.componentId.substringBefore(':', "") + ":"
        if (action !in ACTIONS) return
        val id = interaction.componentId.substringAfter(':').toLongOrNull() ?: return
        if (!requests.claim(interaction.id.toString())) return
        guarded("button") {
            refusal(interaction.user.id, id)?.let {
                interaction.respondEphemeral { content = it }
                return@guarded
            }
            when (action) {
                REMOVE ->
                    interaction.modal("Remove the strike of record #$id", "$REMOVE$id") {
                        actionRow {
                            textInput(TextInputStyle.Paragraph, "reason", "Unban message") {
                                required = true
                                allowedLength = 1..512
                            }
                        }
                    }
                DONE -> {
                    val done = Punishments.markDone(id).isNotEmpty()
                    interaction.respondEphemeral { content = if (done) "Marked the nuke/rollback done." else "It was already marked done." }
                }
                else ->
                    interaction.modal("Evidence for record #$id", "$ADD_EVIDENCE$id") {
                        actionRow {
                            textInput(TextInputStyle.Paragraph, "links", "Clip links, one per line") {
                                placeholder = "https://medal.tv/..."
                                allowedLength = 8..1000
                            }
                        }
                    }
            }
        }
    }

    private suspend fun onModal(event: ModalSubmitInteractionCreateEvent) {
        val interaction = event.interaction
        val action = interaction.modalId.substringBefore(':', "") + ":"
        if (action != REMOVE && action != ADD_EVIDENCE) return
        val id = interaction.modalId.substringAfter(':').toLongOrNull() ?: return
        if (!requests.claim(interaction.id.toString())) return
        guarded("form") {
            val response = interaction.deferEphemeralResponse()
            refusal(interaction.user.id, id)?.let {
                response.respond { content = it }
                return@guarded
            }
            val input = { name: String ->
                interaction.textInputs[name]
                    ?.value
                    ?.trim()
                    .orEmpty()
            }
            val reply =
                if (action == REMOVE) {
                    val user = interaction.user
                    val staff = Staff(links.player(user.id.toString()), user.username.take(32))
                    val lifted = Punishments.removeStrike(id, staff, input("reason").ifEmpty { "Lifted from Discord" })
                    if (lifted.isEmpty()) "Record #$id was already lifted or has expired." else "Removed the strike of record #$id."
                } else {
                    val clips = input("links").lines().map(String::trim).filter { it.startsWith("https://") || it.startsWith("http://") }
                    when {
                        clips.isEmpty() -> "Add at least one http(s) link."
                        Punishments.addEvidence(id, clips.joinToString("\n")).isEmpty() -> "Record #$id doesn't exist."
                        else -> "Added ${clips.size} link(s) to record #$id."
                    }
                }
            response.respond { content = reply }
        }
    }

    /** Missing permissions or a deleted channel must never break punishments. */
    private suspend fun guarded(
        what: String,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            System.err.println("[Discord] Punishment $what failed (${error.javaClass.simpleName})")
        }
    }

    override fun close() {
        if (Punishments.discordLookup === lookup) Punishments.discordLookup = null
        Punishments.listeners.remove(listener)
        Punishments.noticeListeners.remove(noticeListener)
        handlers.forEach { it.cancel() }
    }

    private companion object {
        const val REMOVE = "punish-lift:"
        const val DONE = "punish-done:"
        const val ADD_EVIDENCE = "punish-add-evidence:"
        val ACTIONS = setOf(REMOVE, DONE, ADD_EVIDENCE)
        val OWN_LINE = setOf("Staff", "Reason")
    }
}
