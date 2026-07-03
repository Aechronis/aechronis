package io.github.openminigameserver.worldedit.platform

import BuildInfo
import com.sk89q.worldedit.LocalConfiguration
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.entity.Player
import com.sk89q.worldedit.extension.platform.AbstractPlatform
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extension.platform.Capability
import com.sk89q.worldedit.extension.platform.MultiUserPlatform
import com.sk89q.worldedit.extension.platform.Preference
import com.sk89q.worldedit.internal.Constants
import com.sk89q.worldedit.util.SideEffect
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.registry.Registries
import io.github.openminigameserver.worldedit.MinestomWorldEdit
import io.github.openminigameserver.worldedit.platform.actors.MinestomPlayer
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.adapters.MinestomWorld
import io.github.openminigameserver.worldedit.platform.misc.WorldEditCommand
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.GlobalEventHandler
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.instance.Instance
import org.enginehub.piston.CommandManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MinestomPlatform(
    val extension: MinestomWorldEdit,
) : AbstractPlatform(),
    MultiUserPlatform {
    override fun reload() {
        configuration.load()
        super.reload()
    }

    override fun getRegistries(): Registries = MinestomRegistries

    override fun getDataVersion(): Int = Constants.DATA_VERSION_MC_1_21_11

    override fun setGameHooksEnabled(enabled: Boolean) {
    }

    override fun isValidMobType(type: String): Boolean = EntityType.values().any { it.key().asString() == type }

    override fun matchPlayer(player: Player): Player = player

    override fun matchWorld(world: World): World = world

    override fun registerCommands(commandManager: CommandManager) {
        commandManager.allCommands.forEach {
            MinecraftServer.getCommandManager().register(WorldEditCommand(it))
        }
    }

    override fun registerGameHooks() {
        val handler = MinecraftServer.getGlobalEventHandler()
        val we = WorldEdit.getInstance()

        handler.addListener(PlayerDisconnectEvent::class.java) {
            playerMap.remove(it.player.uuid)
        }
        handleRightClickEvent(handler, we)
        handleLeftClickEvent(handler, we)
    }

    private fun handleLeftClickEvent(
        handler: GlobalEventHandler,
        we: WorldEdit,
    ) {
        handler.addListener(PlayerBlockBreakEvent::class.java) {
            val actor = MinestomAdapter.asActor(it.player) as Player

            if (we.handleBlockLeftClick(
                    actor,
                    MinestomAdapter.asLocation(
                        MinestomAdapter.asWorld(it.player.instance),
                        it.blockPosition,
                    ),
                    null,
                )
            ) {
                it.isCancelled = true
            }
        }
    }

    private fun handleRightClickEvent(
        handler: GlobalEventHandler,
        we: WorldEdit,
    ) {
        handler.addListener(PlayerBlockInteractEvent::class.java) {
            val actor = MinestomAdapter.asActor(it.player) as Player

            if (it.hand == PlayerHand.MAIN &&
                we.handleBlockRightClick(
                    actor,
                    MinestomAdapter.asLocation(
                        MinestomAdapter.asWorld(it.player.instance),
                        it.blockPosition,
                    ),
                    MinestomAdapter.asDirection(it.blockFace),
                )
            ) {
                it.isCancelled = true
            }
        }
    }

    override fun getConfiguration(): LocalConfiguration = extension.config

    override fun getSupportedSideEffects(): MutableSet<SideEffect> = mutableSetOf()

    override fun getConnectedUsers(): MutableCollection<Actor> =
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .map {
                MinestomAdapter.asActor(it)
            }.toMutableList()

    override fun getVersion(): String = platformVersion

    override fun getPlatformName(): String = "WorldEdit-Minestom"

    override fun getPlatformVersion(): String = BuildInfo.VERSION

    override fun getCapabilities(): MutableMap<Capability, Preference> =
        mutableMapOf(
            Capability.CONFIGURATION to Preference.NORMAL,
            Capability.WORLD_EDITING to Preference.NORMAL,
            Capability.GAME_HOOKS to Preference.NORMAL,
            Capability.PERMISSIONS to Preference.NORMAL,
            Capability.WORLDEDIT_CUI to Preference.NORMAL,
            Capability.USER_COMMANDS to Preference.NORMAL,
        )

    fun getWorld(instance: Instance): World = MinestomWorld(instance)

    private val playerMap = ConcurrentHashMap<UUID, MinestomPlayer>()

    fun getPlayer(commandSender: net.minestom.server.entity.Player) =
        playerMap.getOrPut(commandSender.uuid, { MinestomPlayer(this, commandSender) })
}
