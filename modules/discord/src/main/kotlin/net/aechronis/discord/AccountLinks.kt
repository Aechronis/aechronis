package net.aechronis.discord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.aechronis.server.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.UUID

/** One-to-one, UUID-based links. Codes never leave memory or survive a module reload. */
internal class AccountLinks(
    private val path: Path,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Authorization(
        val uuid: UUID,
    )

    private data class Pending(
        val uuid: UUID,
        val expires: Long,
    )

    private val random = SecureRandom()
    private var links = load()
    private val pending = mutableMapOf<String, Pending>()

    @Synchronized
    fun player(discordId: String): UUID? = links[discordId]?.uuid

    @Synchronized
    fun authorization(discordId: String): Authorization? = links[discordId]

    @Synchronized
    fun discord(uuid: UUID): String? = links.entries.firstOrNull { it.value.uuid == uuid }?.key

    @Synchronized
    fun issue(uuid: UUID): String {
        check(discord(uuid) == null) { "Already linked. Use /discord unlink before linking another account." }
        pending.entries.removeIf { it.value.expires <= now() || it.value.uuid == uuid }
        var code: String
        do {
            code =
                buildString(CODE_LENGTH) {
                    repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
                }
        } while (code in pending)
        pending[code] = Pending(uuid, now() + 5 * 60_000)
        return code
    }

    @Synchronized
    fun link(
        discordId: String,
        code: String,
    ): String {
        require(validDiscordId(discordId))
        pending.entries.removeIf { it.value.expires <= now() }
        val claim =
            pending[code.trim().uppercase()]
                ?: return "Invalid or expired code. Run /discord link in Minecraft to get a new one."
        if (player(discordId) != null || discord(claim.uuid) != null) {
            return "An account is already linked. Unlink it first."
        }
        save(links + (discordId to Authorization(claim.uuid)))
        pending.entries.removeIf { it.value.uuid == claim.uuid }
        return "Linked your Minecraft account. You can now use Nodes commands."
    }

    @Synchronized
    fun unlinkDiscord(discordId: String): Boolean {
        val uuid = player(discordId) ?: return false
        save(links - discordId)
        pending.entries.removeIf { it.value.uuid == uuid }
        return true
    }

    @Synchronized
    fun unlinkPlayer(uuid: UUID): Boolean {
        pending.entries.removeIf { it.value.uuid == uuid }
        return discord(uuid)?.let(::unlinkDiscord) ?: false
    }

    /** Serializes unlink/relink with the final game-thread authorization and execution. */
    @Synchronized
    fun <T> withLink(
        discordId: String,
        authorization: Authorization,
        action: () -> T,
    ): T? = if (links[discordId] === authorization) action() else null

    private fun load(): Map<String, Authorization> {
        if (!Files.exists(path)) return emptyMap()
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        require(root["version"]?.jsonPrimitive?.content == "1") { "Unsupported Discord account file" }
        val result = linkedMapOf<String, UUID>()
        root.getValue("links").jsonArray.forEach { element ->
            val entry = element.jsonObject
            val id = entry.getValue("discord").jsonPrimitive.content
            val uuid = UUID.fromString(entry.getValue("player").jsonPrimitive.content)
            require(validDiscordId(id) && id !in result && uuid !in result.values) { "Invalid or duplicate Discord account link" }
            result[id] = uuid
        }
        return result.mapValues { Authorization(it.value) }
    }

    private fun save(updated: Map<String, Authorization>) {
        val data: JsonObject =
            buildJsonObject {
                put("version", 1)
                put(
                    "links",
                    JsonArray(
                        updated.map { (id, uuid) ->
                            buildJsonObject {
                                put("discord", JsonPrimitive(id))
                                put("player", JsonPrimitive(uuid.uuid.toString()))
                            }
                        },
                    ),
                )
            }
        val destination = path.toAbsolutePath()
        AtomicFiles.withTemporaryFile(destination) { temporary ->
            Files.writeString(temporary, data.toString())
            // Fail closed if the filesystem cannot atomically replace the account store.
            AtomicFiles.replace(temporary, destination, requireAtomic = true)
            links = updated
        }
    }

    private fun validDiscordId(value: String): Boolean = value.toULongOrNull()?.let { it > 0u && it.toString() == value } == true

    companion object {
        const val CODE_LENGTH = 6
        private const val CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }
}
