package net.aechronis.vanilla.essentials

import net.aechronis.vanilla.Main
import net.aechronis.vanilla.essentials.commands.BanCommand
import net.aechronis.vanilla.essentials.commands.FlyCommand
import net.aechronis.vanilla.essentials.commands.GameModeCommand
import net.aechronis.vanilla.essentials.commands.GiveCommand
import net.aechronis.vanilla.essentials.commands.IgnoreCommand
import net.aechronis.vanilla.essentials.commands.MessageCommand
import net.aechronis.vanilla.essentials.commands.MuteCommand
import net.aechronis.vanilla.essentials.commands.ReplyCommand
import net.aechronis.vanilla.essentials.commands.TeleportCommand
import net.aechronis.vanilla.essentials.commands.UnBanCommand
import net.aechronis.vanilla.essentials.commands.UnMuteCommand
import net.aechronis.vanilla.essentials.objects.User
import net.luckperms.api.LuckPermsProvider
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import java.util.UUID
import kotlin.collections.get
import kotlin.collections.set

object Essentials {
    val users: LinkedHashMap<UUID, User> = LinkedHashMap()

    fun init() {
        MinecraftServer.getCommandManager().register(MuteCommand())
        MinecraftServer.getCommandManager().register(BanCommand())
        MinecraftServer.getCommandManager().register(UnMuteCommand())
        MinecraftServer.getCommandManager().register(UnBanCommand())
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

    fun Player.getUser(): User? = Essentials.users[this.uuid]

    fun Player.newUser() {
        Essentials.users[this.uuid] =
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
