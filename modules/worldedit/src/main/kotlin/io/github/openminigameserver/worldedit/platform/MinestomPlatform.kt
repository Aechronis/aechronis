package io.github.openminigameserver.worldedit.platform

import com.sk89q.worldedit.LocalConfiguration
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.entity.Player
import com.sk89q.worldedit.event.extent.EditSessionEvent
import com.sk89q.worldedit.extension.platform.AbstractPlatform
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extension.platform.Capability
import com.sk89q.worldedit.extension.platform.MultiUserPlatform
import com.sk89q.worldedit.extension.platform.Preference
import com.sk89q.worldedit.internal.Constants
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.SideEffect
import com.sk89q.worldedit.util.eventbus.EventHandler
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
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerStartDiggingEvent
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
        if (enabled == gameHooksEnabled) return

        val handler = MinecraftServer.getGlobalEventHandler()
        if (enabled) {
            if (!gameHooksRegistered) {
                installGameHooks()
                gameHooksRegistered = true
            }
            handler.addChild(gameHooksNode)
        } else {
            handler.removeChild(gameHooksNode)
        }
        gameHooksEnabled = enabled
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use setGameHooksEnabled(true)")
    override fun registerGameHooks() {
        setGameHooksEnabled(true)
    }

    override fun isValidMobType(type: String): Boolean = EntityType.values().any { it.key().asString() == type }

    override fun matchPlayer(player: Player): Player = player

    override fun matchWorld(world: World): World = world

    override fun registerCommands(commandManager: CommandManager) {
        commandManager.allCommands.forEach {
            MinecraftServer.getCommandManager().register(WorldEditCommand(it))
        }
    }

    private fun installGameHooks() {
        val we = WorldEdit.getInstance()

        gameHooksNode.addListener(PlayerDisconnectEvent::class.java) {
            playerMap.remove(it.player.uuid)
            lastHandledLeftClicks.remove(it.player.uuid)
        }
        handleRightClickEvent(gameHooksNode, we)
        handleLeftClickEvent(gameHooksNode, we)
    }

    private fun handleLeftClickEvent(
        handler: EventNode<Event>,
        we: WorldEdit,
    ) {
        handler.addListener(PlayerStartDiggingEvent::class.java) {
            if (handleLeftClick(we, it.player, it.blockPosition, it.blockFace)) {
                lastHandledLeftClicks[it.player.uuid] = MinestomAdapter.asBlockVector(it.blockPosition)
                it.isCancelled = true
            }
        }
        handler.addListener(PlayerBlockBreakEvent::class.java) {
            val position = MinestomAdapter.asBlockVector(it.blockPosition)
            if (lastHandledLeftClicks.remove(it.player.uuid) == position ||
                handleLeftClick(we, it.player, it.blockPosition, it.blockFace)
            ) {
                it.isCancelled = true
            }
        }
    }

    private fun handleLeftClick(
        we: WorldEdit,
        player: net.minestom.server.entity.Player,
        blockPosition: net.minestom.server.coordinate.BlockVec,
        blockFace: net.minestom.server.instance.block.BlockFace,
    ): Boolean {
        val actor = MinestomAdapter.asActor(player) as Player
        return we.handleBlockLeftClick(
            actor,
            MinestomAdapter.asLocation(
                MinestomAdapter.asWorld(player.instance),
                blockPosition,
            ),
            MinestomAdapter.asDirection(blockFace),
        )
    }

    private fun handleRightClickEvent(
        handler: EventNode<Event>,
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

    override fun getPlatformVersion(): String = "minestom"

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

    fun registerWorldEditEventHandlers() {
        if (worldEditEventHandlersRegistered) return
        WorldEdit.getInstance().eventBus.subscribe(EditSessionEvent::class.java, editSessionEventHandler)
        worldEditEventHandlersRegistered = true
    }

    fun unregisterWorldEditEventHandlers() {
        if (!worldEditEventHandlersRegistered) return
        WorldEdit.getInstance().eventBus.unsubscribe(EditSessionEvent::class.java, editSessionEventHandler)
        worldEditEventHandlersRegistered = false
    }

    private val playerMap = ConcurrentHashMap<UUID, MinestomPlayer>()

    fun getPlayer(commandSender: net.minestom.server.entity.Player) =
        playerMap.getOrPut(commandSender.uuid, { MinestomPlayer(this, commandSender) })

    private val gameHooksNode: EventNode<Event> = EventNode.all("worldedit-game-hooks")
    private var gameHooksEnabled = false
    private var gameHooksRegistered = false
    private val lastHandledLeftClicks = ConcurrentHashMap<UUID, BlockVector3>()
    private var worldEditEventHandlersRegistered = false
    private val editSessionEventHandler =
        object : EventHandler(EventHandler.Priority.VERY_EARLY) {
            override fun dispatch(event: Any) {
                if (event !is EditSessionEvent) return
                (event.world as? MinestomWorld)?.setActor(event.actor)
            }

            override fun hashCode(): Int = System.identityHashCode(this)

            override fun equals(other: Any?): Boolean = this === other
        }
}
