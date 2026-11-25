package net.aechronis.vanilla.listeners

import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Stops players standing on or walking into a block the server refused to place.
 *
 * The client shows the block the moment it clicks and only removes it when the server's rollback arrives
 * a round trip later, so it can collide with it for a moment and pillar up or bridge across.
 * For a short while after a refused placement, any move onto that spot is sent back.
 */
object GhostBlockListener {
    private const val GUARD_MILLIS = 1_000L

    // Landing on top of a block leaves the feet exactly on its top face.
    private const val TOP_TOLERANCE = 0.001

    private class Ghost(
        val instance: Instance,
        val x: Int,
        val y: Int,
        val z: Int,
        val block: Block,
        val until: Long,
    )

    private val ghosts = ConcurrentHashMap<UUID, MutableList<Ghost>>()

    // Runs after every protection listener so it sees their cancellations.
    private val placeNode =
        EventNode
            .all("vanilla-ghost-block-place")
            .setPriority(1_000)
            .addListener(
                EventListener
                    .builder(PlayerBlockPlaceEvent::class.java)
                    .ignoreCancelled(false)
                    .handler(::onPlace)
                    .build(),
            )

    // Runs before the other movement listeners so none of them act on a move that gets sent back.
    private val moveNode =
        EventNode
            .all("vanilla-ghost-block-move")
            .setPriority(-1_000)
            .addListener(PlayerMoveEvent::class.java, ::onMove)
            .addListener(PlayerDisconnectEvent::class.java) { ghosts.remove(it.player.uuid) }

    private fun onPlace(event: PlayerBlockPlaceEvent) {
        if (!event.isCancelled) return
        // Flying staff in creative or spectator are not the stackers this is for.
        if (event.player.gameMode == GameMode.CREATIVE || event.player.gameMode == GameMode.SPECTATOR) return
        val pos = event.blockPosition
        // A solid block already there is real; the client has nothing to be wrong about.
        val instance = event.player.instance ?: return
        if (instance.getBlock(pos).registry().isSolid()) return
        val until = System.currentTimeMillis() + GUARD_MILLIS
        val list = ghosts.getOrPut(event.player.uuid) { java.util.concurrent.CopyOnWriteArrayList() }
        list.removeAll { it.until < System.currentTimeMillis() }
        list.add(Ghost(instance, pos.blockX(), pos.blockY(), pos.blockZ(), event.block, until))
    }

    private fun onMove(event: PlayerMoveEvent) {
        if (event.isCancelled) return
        val player = event.player
        val list = ghosts[player.uuid] ?: return
        val now = System.currentTimeMillis()
        list.removeAll { it.until < now }
        if (list.isEmpty()) {
            ghosts.remove(player.uuid, list)
            return
        }

        // A player already touching the spot may walk out of it.
        val blocked =
            list.any { ghost ->
                ghost.instance === player.instance &&
                    touches(player, event.newPosition, ghost) &&
                    !touches(player, player.position, ghost)
            }
        if (blocked) event.isCancelled = true
    }

    private fun touches(
        player: Player,
        position: Point,
        ghost: Ghost,
    ): Boolean {
        // Shifting down a hair makes landing on the top face count as touching.
        val relative = Vec(position.x() - ghost.x, position.y() - ghost.y - TOP_TOLERANCE, position.z() - ghost.z)
        val shape = ghost.block.registry().collisionShape()
        return shape.intersectBox(relative, player.boundingBox)
    }

    fun init() {
        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        ModuleEvents.addChild(globalEventHandler, placeNode)
        ModuleEvents.addChild(globalEventHandler, moveNode)
    }
}
