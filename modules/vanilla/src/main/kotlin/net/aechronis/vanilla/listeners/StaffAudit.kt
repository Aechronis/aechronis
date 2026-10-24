package net.aechronis.vanilla.listeners

import net.aechronis.server.hasPermission
import net.minestom.server.entity.Player
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Posts what staff do to a Discord channel, one thread per staff member, plus a pinned index of the threads.
 * Needs DISCORD_BOT_TOKEN and DISCORD_STAFF_LOG_CHANNEL_ID; silently off without them.
 */
object StaffAudit {
    private const val PERMISSION = "vanilla.audit"
    private const val API = "https://discord.com/api/v10"
    private const val INDEX_KEY = "index"
    private const val COMMAND_COLOR = 0x5865F2
    private const val CREATIVE_COLOR = 0xFAA61A
    private const val INDEX_COLOR = 0x2B2D31

    private val token = System.getenv("DISCORD_BOT_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    private val channel = System.getenv("DISCORD_STAFF_LOG_CHANNEL_ID")?.trim()?.takeIf { it.isNotEmpty() }
    private val file = Path.of("staff-audit-threads.properties")
    private val LOG = Path.of("staff-audit.log")

    // uuid -> "threadId|name"; INDEX_KEY -> id of the pinned index message
    private val store = Properties().apply { if (Files.exists(file)) Files.newBufferedReader(file).use(::load) }
    private val http = HttpClient.newHttpClient()

    // One worker keeps entries in order and never blocks the tick thread.
    // ponytail: no 429 handling, entries over Discord's rate limit are dropped; add retry_after backoff if staff are busy
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "staff-audit").apply { isDaemon = true } }

    private val seen = mutableSetOf<UUID>()

    private class Entry(
        val thread: String,
        val name: String,
    )

    fun audited(player: Player) = token != null && channel != null && player.hasPermission(PERMISSION)

    fun command(
        player: Player,
        command: String,
    ) = post(player, COMMAND_COLOR, "Command", "```\n/${command.replace("```", "'''").take(1_800)}\n```", "/$command")

    fun creative(
        player: Player,
        amount: Int,
        material: String,
    ) = post(player, CREATIVE_COLOR, "Creative inventory", "Took **${amount}x** `$material`", "creative: took ${amount}x $material")

    private fun post(
        player: Player,
        color: Int,
        title: String,
        description: String,
        plain: String,
    ) {
        val name = player.username
        val uuid = player.uuid
        val pos = player.position
        val where = "%d, %d, %d".format(pos.blockX(), pos.blockY(), pos.blockZ())
        val embed =
            """{"author":{"name":${json(name)},"icon_url":"https://mc-heads.net/avatar/$uuid/32"},""" +
                """"title":${json(title)},"description":${json(description)},"color":$color,""" +
                """"footer":{"text":${json(where)}},"timestamp":"${Instant.now()}"}"""
        val body = """{"allowed_mentions":{"parse":[]},"embeds":[$embed]}"""
        // Tab-separated: epoch millis, uuid, name, text. Read by the Discord /audit search command.
        val line = "${System.currentTimeMillis()}\t$uuid\t$name\t${plain.replace(Regex("\\s+"), " ").take(1_900)}\n"
        worker.execute {
            runCatching { Files.writeString(LOG, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND) }
                .onFailure { System.err.println("[StaffAudit] log write failed: ${it.message}") }
            runCatching {
                try {
                    send("POST", "/channels/${sync(uuid, name)}/messages", body)
                } catch (_: Missing) {
                    // Thread was deleted in Discord: forget it, make a fresh one and re-list it in the index.
                    store.remove(uuid.toString())
                    send("POST", "/channels/${sync(uuid, name)}/messages", body)
                }
            }.onFailure { System.err.println("[StaffAudit] ${it.message}") }
        }
    }

    /** Creates or renames the staff member's thread when new or their name changed, then refreshes the index. */
    private fun sync(
        uuid: UUID,
        name: String,
    ): String {
        val known = entry(uuid.toString())
        if (known != null && known.name == name) {
            // First post per session also repairs a deleted index message.
            if (seen.add(uuid)) refreshIndex()
            return known.thread
        }
        seen.add(uuid)
        val threadName = json(name)
        val thread =
            if (known == null) {
                val body = """{"name":$threadName,"type":11,"auto_archive_duration":10080}"""
                idOf(send("POST", "/channels/$channel/threads", body))
            } else {
                send("PATCH", "/channels/${known.thread}", """{"name":$threadName}""")
                known.thread
            }
        store.setProperty(uuid.toString(), "$thread|$name")
        refreshIndex()
        return thread
    }

    private fun refreshIndex() {
        val lines =
            store
                .stringPropertyNames()
                .filter { it != INDEX_KEY }
                .mapNotNull { entry(it) }
                .sortedBy { it.name.lowercase() }
                .joinToString("\n") { "<#${it.thread}>" }
        val body =
            """{"allowed_mentions":{"parse":[]},"embeds":[{"title":"Staff audit threads","color":$INDEX_COLOR,""" +
                """"description":${json(lines.take(4_000))},"footer":{"text":"Sorted by name"}}]}"""
        val existing = store.getProperty(INDEX_KEY)
        val edited = existing != null && runCatching { send("PATCH", "/channels/$channel/messages/$existing", body) }.isSuccess
        if (!edited) {
            val id = idOf(send("POST", "/channels/$channel/messages", body))
            store.setProperty(INDEX_KEY, id)
            runCatching { send("PUT", "/channels/$channel/pins/$id", null) }
        }
        Files.newBufferedWriter(file).use { store.store(it, null) }
    }

    private class Missing : RuntimeException("Discord 404")

    private fun entry(key: String): Entry? =
        store
            .getProperty(key)
            ?.split('|', limit = 2)
            ?.takeIf { it.size == 2 }
            ?.let { Entry(it[0], it[1]) }

    private fun idOf(response: String) = Regex("\"id\"\\s*:\\s*\"(\\d+)\"").find(response)!!.groupValues[1]

    private fun send(
        method: String,
        path: String,
        body: String?,
    ): String {
        val request =
            HttpRequest
                .newBuilder(URI.create(API + path))
                .header("Authorization", "Bot $token")
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordBot (aechronis, 1.0)")
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) throw Missing()
        check(response.statusCode() in 200..299) { "Discord ${response.statusCode()}: ${response.body().take(200)}" }
        return response.body()
    }

    private fun json(text: String): String =
        buildString {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c == '\n' -> append("\\n")
                    c < ' ' -> append(' ')
                    else -> append(c)
                }
            }
            append('"')
        }
}
