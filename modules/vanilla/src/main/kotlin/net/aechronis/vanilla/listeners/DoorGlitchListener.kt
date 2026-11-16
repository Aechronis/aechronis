package net.aechronis.vanilla.listeners

import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockTags
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Stops players walking through a door, trapdoor or fence gate that a protection refused to open.
 *
 * The client opens it the moment it clicks and only shuts it when the server's rollback arrives a
 * round trip later, so spam-clicking keeps it open on their screen long enough to walk through.
 * For a short while after a refused click, any move into one the server still has closed is sent back.
 */
object DoorGlitchListener {
    private const val GUARD_MILLIS = 1_500L
    private val OPENABLE_TAGS = listOf(BlockTags.DOORS, BlockTags.TRAPDOORS, BlockTags.FENCE_GATES)

    private val guardedUntil = ConcurrentHashMap<UUID, Long>()

    // Runs after every protection listener so it sees their cancellations.
    private val interactNode =
        EventNode
            .all("vanilla-door-glitch-interact")
            .setPriority(1_000)
            .addListener(
                EventListener
                    .builder(PlayerBlockInteractEvent::class.java)
                    .ignoreCancelled(false)
                    .handler(::onInteract)
                    .build(),
            )

    // Runs before the other movement listeners so none of them act on a move that gets sent back.
    private val moveNode =
        EventNode
            .all("vanilla-door-glitch-move")
            .setPriority(-1_000)
            .addListener(PlayerMoveEvent::class.java, ::onMove)
            .addListener(PlayerDisconnectEvent::class.java) { guardedUntil.remove(it.player.uuid) }

    private fun onInteract(event: PlayerBlockInteractEvent) {
        if (!event.isCancelled || !isOpenable(event.block)) return
        guardedUntil[event.player.uuid] = System.currentTimeMillis() + GUARD_MILLIS
    }

    private fun onMove(event: PlayerMoveEvent) {
        if (event.isCancelled) return
        val player = event.player
        val until = guardedUntil[player.uuid] ?: return
        if (System.currentTimeMillis() > until) {
            guardedUntil.remove(player.uuid, until)
            return
        }

        // A player already overlapping a closed door may walk out of it.
        if (overlapsClosedOpenable(player, event.newPosition) && !overlapsClosedOpenable(player, player.position)) {
            event.isCancelled = true
        }
    }

    // An open door's collision shape is the thin panel at its side, so only a shut one blocks the doorway.
    private fun overlapsClosedOpenable(
        player: Player,
        position: Pos,
    ): Boolean {
        val instance = player.instance ?: return false
        val box = player.boundingBox
        val minX = floor(position.x + box.minX()).toInt()
        val maxX = floor(position.x + box.maxX() - Vec.EPSILON).toInt()
        val minY = floor(position.y + box.minY()).toInt()
        val maxY = floor(position.y + box.maxY() - Vec.EPSILON).toInt()
        val minZ = floor(position.z + box.minZ()).toInt()
        val maxZ = floor(position.z + box.maxZ() - Vec.EPSILON).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val block = instance.getBlock(x, y, z)
                    if (!isOpenable(block)) continue
                    val blockPosition = Vec(x.toDouble(), y.toDouble(), z.toDouble())
                    if (block.registry().collisionShape().intersectBox(position.sub(blockPosition), box)) return true
                }
            }
        }
        return false
    }

    private fun isOpenable(block: Block): Boolean {
        val tags = MinecraftServer.process().blocks()
        return OPENABLE_TAGS.any { tags.getTag(it)?.contains(block) == true }
    }

    fun init() {
        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        ModuleEvents.addChild(globalEventHandler, interactNode)
        ModuleEvents.addChild(globalEventHandler, moveNode)
    }
}
