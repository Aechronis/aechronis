package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerTickEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockTags
import net.minestom.server.potion.PotionEffect
import net.minestom.server.registry.TagKey
import kotlin.math.floor

/** Vanilla's fall distance for melee crits. The Vanilla module depends on Combat, so its fall tracker can't be used here. */
object FallDistanceListener {
    private fun onPlayerMove(event: PlayerMoveEvent) {
        if (event.isCancelled) return

        val player = event.player
        val state = Combat.playerStates.getOrCreate(player)
        val deltaY = event.newPosition.y - player.position.y

        if (event.isOnGround || isFallSuspended(player)) {
            state.fallDistance = 0.0
            return
        }
        if (state.fallDistance == 0.0 && deltaY >= 0.0) return

        if (touchesFallResettingBlock(player, event.newPosition)) {
            state.fallDistance = 0.0
        } else if (deltaY < 0.0) {
            state.fallDistance -= deltaY
        }
    }

    private fun onPlayerTick(event: PlayerTickEvent) {
        if (event.player.vehicle != null) Combat.playerStates.getOrCreate(event.player).fallDistance = 0.0
    }

    private fun isFallSuspended(player: Player): Boolean =
        player.vehicle != null ||
            player.isFlying ||
            player.hasEffect(PotionEffect.SLOW_FALLING) ||
            player.hasEffect(PotionEffect.LEVITATION)

    internal fun isInWater(player: Player): Boolean = blocksTouching(player, player.position).any(::isWater)

    internal fun isOnClimbable(player: Player): Boolean {
        val instance = player.instance ?: return false
        val position = player.position
        val block = instance.getBlock(position, Block.Getter.Condition.TYPE) ?: return false
        if (blockIsInTag(block, BlockTags.CLIMBABLE)) return true
        if (!blockIsInTag(block, BlockTags.TRAPDOORS) || block.properties()["open"] != "true") return false

        // an open trapdoor on top of a ladder climbs like one when both face the same way
        val below = instance.getBlock(position.sub(0.0, 1.0, 0.0), Block.Getter.Condition.TYPE) ?: return false
        return below.id() == Block.LADDER.id() && below.properties()["facing"] == block.properties()["facing"]
    }

    private fun touchesFallResettingBlock(
        player: Player,
        position: Pos,
    ): Boolean =
        blocksTouching(player, position).any { block ->
            isWater(block) || blockIsInTag(block, BlockTags.CLIMBABLE) || blockIsInTag(block, BlockTags.FALL_DAMAGE_RESETTING)
        }

    private fun isWater(block: Block): Boolean = block.id() == Block.WATER.id() || block.properties()["waterlogged"] == "true"

    private fun blocksTouching(
        player: Player,
        position: Pos,
    ): Sequence<Block> =
        sequence {
            val instance = player.instance ?: return@sequence
            val box = player.boundingBox
            val epsilon = Point.EPSILON
            for (x in floor(position.x + box.minX() + epsilon).toInt()..floor(position.x + box.maxX() - epsilon).toInt()) {
                for (y in floor(position.y + box.minY() + epsilon).toInt()..floor(position.y + box.maxY() - epsilon).toInt()) {
                    for (z in floor(position.z + box.minZ() + epsilon).toInt()..floor(position.z + box.maxZ() - epsilon).toInt()) {
                        instance.getBlock(x, y, z, Block.Getter.Condition.TYPE)?.let { yield(it) }
                    }
                }
            }
        }

    private fun blockIsInTag(
        block: Block,
        tag: TagKey<Block>,
    ): Boolean =
        MinecraftServer
            .process()
            .blocks()
            .getTag(tag)
            ?.contains(block) == true

    fun init() {
        Combat.eventNode.addListener(PlayerMoveEvent::class.java, FallDistanceListener::onPlayerMove)
        Combat.eventNode.addListener(PlayerTickEvent::class.java, FallDistanceListener::onPlayerTick)
    }
}
