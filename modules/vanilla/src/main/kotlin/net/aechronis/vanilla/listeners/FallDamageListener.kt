package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.minestom.server.MinecraftServer
import net.minestom.server.collision.CollisionUtils
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockTags
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment
import net.minestom.server.potion.PotionEffect
import net.minestom.server.registry.RegistryKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

object FallDamageListener {
    private val FALL: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:fall")
    private const val SUPPORT_PROBE = 1.0E-5
    private const val VANILLA_DAMAGE_EPSILON = 1.0E-6

    private val fallDistance = ConcurrentHashMap<UUID, Double>()

    fun onMove(event: PlayerMoveEvent) {
        if (event.isCancelled) return

        val player = event.player
        if (doesNotAccumulateFallDamage(player)) {
            reset(player)
            return
        }

        val from = player.position
        val to = event.newPosition
        val movement = to.sub(from).asVec()

        if (touchesWater(player, to) || touchesFallResettingBlock(player, to)) {
            reset(player)
            return
        }

        if (movement.y() < 0.0) {
            // Minecraft converts each movement delta to float before adding it to its double field.
            fallDistance.merge(player.uuid, -movement.y().toFloat().toDouble(), Double::plus)
        }

        if (player.hasEffect(PotionEffect.SLOW_FALLING)) {
            fallDistance.computeIfPresent(player.uuid) { _, distance -> distance.coerceAtMost(1.0) }
        }

        if (!event.isOnGround) return

        val support =
            findSupport(player, to)
                ?: findSupport(player, to.add(-movement.x(), 0.0, -movement.z()))
                ?: return

        land(player, support.block)
    }

    fun onTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return

        if (findSupport(player, player.position) != null) reset(player)
    }

    fun onSpawn(event: PlayerSpawnEvent) {
        reset(event.player)
    }

    fun onDeath(event: PlayerDeathEvent) {
        reset(event.player)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        reset(event.player)
    }

    fun reset(player: Player) {
        fallDistance.remove(player.uuid)
    }

    private fun land(
        player: Player,
        block: Block,
    ) {
        val distance = fallDistance.remove(player.uuid) ?: return
        if (distance <= 0.0 || player.health <= 0f) return

        val safeDistance = player.getAttributeValue(Attribute.SAFE_FALL_DISTANCE)
        val entityMultiplier = player.getAttributeValue(Attribute.FALL_DAMAGE_MULTIPLIER)
        val blockMultiplier = landingMultiplier(block)
        val rawDamage =
            floor(
                (distance + VANILLA_DAMAGE_EPSILON - safeDistance) *
                    blockMultiplier *
                    entityMultiplier,
            ).toFloat()
        val damage = applyFallProtection(player, rawDamage)

        if (damage > 0f) player.damage(FALL, damage)
    }

    private fun applyFallProtection(
        player: Player,
        damage: Float,
    ): Float {
        if (damage <= 0f) return damage

        var protection = 0
        for (slot in EquipmentSlot.armors()) {
            val enchantments = player.getEquipment(slot).get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY)
            protection += enchantments.level(Enchantment.PROTECTION)
            protection += enchantments.level(Enchantment.FEATHER_FALLING) * 3
        }
        return damage * (1f - protection.coerceIn(0, 20) * 0.04f)
    }

    private fun landingMultiplier(block: Block): Double =
        when {
            block.id() == Block.HAY_BLOCK.id() -> 0.2
            block.id() == Block.HONEY_BLOCK.id() -> 0.2
            block.id() == Block.SLIME_BLOCK.id() -> 0.0
            blockIsInTag(block, BlockTags.BEDS) -> 0.5
            else -> 1.0
        }

    private fun doesNotAccumulateFallDamage(player: Player): Boolean =
        player.gameMode == GameMode.CREATIVE ||
            player.gameMode == GameMode.SPECTATOR ||
            player.vehicle != null ||
            player.isFlying ||
            player.isFlyingWithElytra

    private fun findSupport(
        player: Player,
        position: Pos,
    ): Support? {
        val instance = player.instance ?: return null
        val result =
            CollisionUtils.handlePhysics(
                instance,
                instance.getChunkAt(position),
                player.boundingBox,
                position,
                Vec(0.0, -SUPPORT_PROBE, 0.0),
                null,
                true,
            )
        if (!result.isOnGround || !result.collisionY()) return null

        val blockPosition = result.collisionShapePositions()[1] ?: return null
        val block = instance.getBlock(blockPosition, Block.Getter.Condition.TYPE) ?: return null
        return Support(block)
    }

    private fun touchesWater(
        player: Player,
        position: Pos,
    ): Boolean =
        blocksTouching(player, position).any { block ->
            block.id() == Block.WATER.id() || block.properties()["waterlogged"] == "true"
        }

    private fun touchesFallResettingBlock(
        player: Player,
        position: Pos,
    ): Boolean =
        blocksTouching(player, position).any { block ->
            blockIsInTag(block, BlockTags.CLIMBABLE) || blockIsInTag(block, BlockTags.FALL_DAMAGE_RESETTING)
        }

    private fun blocksTouching(
        player: Player,
        position: Pos,
    ): Sequence<Block> =
        sequence {
            val instance = player.instance ?: return@sequence
            val box = player.boundingBox
            val epsilon = Point.EPSILON
            val minX = floor(position.x() + box.minX() + epsilon).toInt()
            val maxX = floor(position.x() + box.maxX() - epsilon).toInt()
            val minY = floor(position.y() + box.minY() + epsilon).toInt()
            val maxY = floor(position.y() + box.maxY() - epsilon).toInt()
            val minZ = floor(position.z() + box.minZ() + epsilon).toInt()
            val maxZ = floor(position.z() + box.maxZ() - epsilon).toInt()

            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        instance.getBlock(x, y, z, Block.Getter.Condition.TYPE)?.let { yield(it) }
                    }
                }
            }
        }

    private fun blockIsInTag(
        block: Block,
        tag: net.minestom.server.registry.TagKey<Block>,
    ): Boolean =
        MinecraftServer
            .process()
            .blocks()
            .getTag(tag)
            ?.contains(block) == true

    fun init() {
        Vanilla.eventNode.addListener(PlayerMoveEvent::class.java, FallDamageListener::onMove)
        Vanilla.eventNode.addListener(EntityTeleportEvent::class.java, FallDamageListener::onTeleport)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, FallDamageListener::onSpawn)
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, FallDamageListener::onDeath)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, FallDamageListener::onDisconnect)
    }

    private data class Support(
        val block: Block,
    )
}
