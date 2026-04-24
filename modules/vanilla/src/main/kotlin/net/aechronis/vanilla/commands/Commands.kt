package net.aechronis.vanilla.commands

import net.luckperms.api.LuckPermsProvider
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import java.util.UUID

data class User(
    val uuid: UUID, // player's UUID
    val name: String, // username
    val mutedTime: Long, // timestamp of mute time
    val bannedTime: Long, // timestamp of ban time
    val mutes: HashMap<Long, String>, // timestamp of mute time to reason
    val bans: HashMap<Long, String>, // timestamp of ban time to reason
    val ignored: HashSet<UUID>, // ignored players
    val lastMessage: UUID?, // last player messaged
)

object Commands {
    val users: LinkedHashMap<UUID, User> = LinkedHashMap()

    fun init() {
        MinecraftServer.getCommandManager().register(GameModeCommand())
        MinecraftServer.getCommandManager().register(FlyCommand())
        MinecraftServer.getCommandManager().register(GiveCommand())
        MinecraftServer.getCommandManager().register(TeleportCommand())
        MinecraftServer.getCommandManager().register(MessageCommand())
        MinecraftServer.getCommandManager().register(ReplyCommand())
        MinecraftServer.getCommandManager().register(IgnoreCommand())
    }

    fun Player.hasPermission(permission: String): Boolean =
        try {
            LuckPermsProvider
                .get()
                .userManager
                .getUser(this.uuid)
                ?.cachedData
                ?.permissionData
                ?.checkPermission(permission)
                ?.asBoolean()
                ?: false
        } catch (_: Exception) {
            false
        }

    fun Player.getUser(): User? = Commands.users[this.uuid]

    fun Player.newUser() {
        Commands.users[this.uuid] =
            User(
                uuid = this.uuid,
                name = this.username,
                mutedTime = 0L,
                bannedTime = 0L,
                mutes = HashMap(),
                bans = HashMap(),
                ignored = HashSet(),
                lastMessage = null,
            )
    }
}
