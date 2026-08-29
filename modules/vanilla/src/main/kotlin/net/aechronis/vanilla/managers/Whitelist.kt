package net.aechronis.vanilla.managers

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.aechronis.utils.hasPermission
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Whitelist {
    const val WEAK_TIER = 1
    const val SUPER_ADMIN_TIER = 2
    const val BYPASS_PERMISSION = "vanilla.whitelist"

    data class Entry(
        val uuid: String?,
        val name: String,
        val tier: Int = WEAK_TIER,
    )

    var enabled: Boolean = false
        private set

    // lowercase name -> entry
    private val entries = ConcurrentHashMap<String, Entry>()
    private val gson = Gson()

    private lateinit var entriesFile: Path
    private lateinit var stateFile: Path

    fun init(path: Path) {
        entriesFile = path
        stateFile = path.resolveSibling("whitelist-enabled.txt")
        Files.createDirectories(path.parent)

        load()
    }

    fun isWhitelistedName(name: String): Boolean = entries.containsKey(name.lowercase())

    /** Returns the tier explicitly assigned to a whitelist entry, if any. */
    fun whitelistTier(
        uuid: UUID,
        name: String,
    ): Int? = findEntry(uuid, name)?.tier

    /**
     * Returns a player's effective access tier. The permission bypass is deliberately
     * limited to [WEAK_TIER]; only an explicit tier-two entry grants super-admin access.
     */
    fun accessTier(
        uuid: UUID,
        name: String,
    ): Int = maxOf(whitelistTier(uuid, name) ?: 0, if (uuid.hasPermission(BYPASS_PERMISSION)) WEAK_TIER else 0)

    fun isWhitelisted(
        uuid: UUID,
        name: String,
    ): Boolean = accessTier(uuid, name) >= WEAK_TIER

    fun add(
        name: String,
        tier: Int = WEAK_TIER,
    ) {
        require(tier in WEAK_TIER..SUPER_ADMIN_TIER) { "Whitelist tier must be between $WEAK_TIER and $SUPER_ADMIN_TIER" }
        entries[name.lowercase()] = Entry(resolveUuid(name), name, tier)
        save()
    }

    fun remove(name: String) {
        entries.remove(name.lowercase())
        save()
    }

    fun toggle(): Boolean {
        enabled = !enabled
        saveState()
        return enabled
    }

    fun enforce() {
        enabled = true
        saveState()

        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            if (!isWhitelisted(player.uuid, player.username)) {
                player.kick(Component.text("You are not whitelisted on this server"))
            }
        }
    }

    private fun findEntry(
        uuid: UUID,
        name: String,
    ): Entry? {
        val uuidString = uuid.toString()
        return entries.values.firstOrNull { entry ->
            entry.uuid?.equals(uuidString, ignoreCase = true)
                ?: entry.name.equals(name, ignoreCase = true)
        }
    }

    private fun resolveUuid(name: String): String? =
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .firstOrNull { it.username.equals(name, ignoreCase = true) }
            ?.uuid
            ?.toString()

    private fun load() {
        if (Files.exists(entriesFile)) {
            runCatching {
                Files.newBufferedReader(entriesFile).use { reader ->
                    val type = object : TypeToken<List<Entry>>() {}.type
                    val loaded: List<Entry>? = gson.fromJson(reader, type)
                    loaded?.forEach { entry ->
                        // Older whitelist files have no tier, which Gson reads as zero.
                        val tier = entry.tier.takeIf { it in WEAK_TIER..SUPER_ADMIN_TIER } ?: WEAK_TIER
                        entries[entry.name.lowercase()] = Entry(entry.uuid, entry.name, tier)
                    }
                }
            }.onFailure { error ->
                System.err.println("Failed to load whitelist: ${error.message}")
            }
        }

        if (Files.exists(stateFile)) {
            enabled = Files.readString(stateFile).trim().toBoolean()
        }
    }

    private fun save() {
        Files.newBufferedWriter(entriesFile).use { writer ->
            gson.toJson(entries.values.toList(), writer)
        }
    }

    private fun saveState() {
        Files.writeString(stateFile, enabled.toString())
    }
}
