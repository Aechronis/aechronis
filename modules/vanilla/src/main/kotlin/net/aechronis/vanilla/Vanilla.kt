package net.aechronis.vanilla

import net.aechronis.vanilla.commands.FlyCommand
import net.aechronis.vanilla.commands.GameModeCommand
import net.aechronis.vanilla.commands.MessageCommand
import net.aechronis.vanilla.commands.ReplyCommand
import net.aechronis.vanilla.commands.TeleportCommand
import net.aechronis.vanilla.playerdata.PlayerData
import net.aechronis.vanilla.recipes.Recipes
import net.aechronis.vanilla.recipes.listeners.CloseListener
import net.aechronis.vanilla.recipes.listeners.ConnectionListener
import net.aechronis.vanilla.recipes.listeners.GridListener
import net.aechronis.vanilla.recipes.listeners.ShiftClickListener
import net.aechronis.vanilla.recipes.listeners.TableListener
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import java.nio.file.Path
import java.sql.Connection

object Vanilla {
    val lowPriorityEventNode = EventNode.all("vanilla-low-priority").setPriority(999)
    val eventNode = EventNode.all("vanilla")
    val highPriorityEventNode = EventNode.all("vanilla-high-priority").setPriority(-999)

    fun init(config: VanillaConfig = VanillaConfig()) {
        // measure load time
        val timeStart = System.currentTimeMillis()

        // init commands
        MinecraftServer.getCommandManager().register(
            MessageCommand(),
            ReplyCommand(),
            GameModeCommand(),
            TeleportCommand(),
            FlyCommand(),
        )
        // init listeners
        CloseListener.init()
        ConnectionListener.init()
        GridListener.init()
        ShiftClickListener.init()
        TableListener.init()

        PlayerData.init(Path.of(config.playerDataPath))
        Recipes.init()
        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Enabled in ${timeLoad}ms")
    }
}
