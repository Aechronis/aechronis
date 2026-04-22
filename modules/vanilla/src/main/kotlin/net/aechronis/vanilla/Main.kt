package net.aechronis.vanilla

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

object Main {
    fun init(config: Config = Config()) {
        // measure load time
        val timeStart = System.currentTimeMillis()
        MinecraftServer
            .getInstanceManager()
            .instances
            .firstOrNull()
            ?.timeSynchronizationTicks = 0

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Enabled in ${timeLoad}ms")
    }
}
