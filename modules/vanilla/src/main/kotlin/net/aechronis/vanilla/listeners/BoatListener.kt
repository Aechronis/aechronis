package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.minestom.server.collision.BoundingBox
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.math.floor

object BoatListener {
    private const val PLACEMENT_RANGE = 5.0
    private const val RAY_STEP = 0.05

    private val boatTypes =
        mapOf(
            Material.OAK_BOAT to EntityType.OAK_BOAT,
            Material.OAK_CHEST_BOAT to EntityType.OAK_CHEST_BOAT,
            Material.SPRUCE_BOAT to EntityType.SPRUCE_BOAT,
            Material.SPRUCE_CHEST_BOAT to EntityType.SPRUCE_CHEST_BOAT,
            Material.BIRCH_BOAT to EntityType.BIRCH_BOAT,
            Material.BIRCH_CHEST_BOAT to EntityType.BIRCH_CHEST_BOAT,
            Material.JUNGLE_BOAT to EntityType.JUNGLE_BOAT,
            Material.JUNGLE_CHEST_BOAT to EntityType.JUNGLE_CHEST_BOAT,
            Material.ACACIA_BOAT to EntityType.ACACIA_BOAT,
            Material.ACACIA_CHEST_BOAT to EntityType.ACACIA_CHEST_BOAT,
            Material.CHERRY_BOAT to EntityType.CHERRY_BOAT,
            Material.CHERRY_CHEST_BOAT to EntityType.CHERRY_CHEST_BOAT,
            Material.DARK_OAK_BOAT to EntityType.DARK_OAK_BOAT,
            Material.DARK_OAK_CHEST_BOAT to EntityType.DARK_OAK_CHEST_BOAT,
            Material.PALE_OAK_BOAT to EntityType.PALE_OAK_BOAT,
            Material.PALE_OAK_CHEST_BOAT to EntityType.PALE_OAK_CHEST_BOAT,
            Material.MANGROVE_BOAT to EntityType.MANGROVE_BOAT,
            Material.MANGROVE_CHEST_BOAT to EntityType.MANGROVE_CHEST_BOAT,
            Material.BAMBOO_RAFT to EntityType.BAMBOO_RAFT,
            Material.BAMBOO_CHEST_RAFT to EntityType.BAMBOO_CHEST_RAFT,
        )

    private val boatEntityTypes = boatTypes.values.toSet()
    private val boatItems = boatTypes.entries.associate { (material, entityType) -> entityType to material }

    private val chestBoatEntityTypes =
        setOf(
            EntityType.OAK_CHEST_BOAT,
            EntityType.SPRUCE_CHEST_BOAT,
            EntityType.BIRCH_CHEST_BOAT,
            EntityType.JUNGLE_CHEST_BOAT,
            EntityType.ACACIA_CHEST_BOAT,
            EntityType.CHERRY_CHEST_BOAT,
            EntityType.DARK_OAK_CHEST_BOAT,
            EntityType.PALE_OAK_CHEST_BOAT,
            EntityType.MANGROVE_CHEST_BOAT,
            EntityType.BAMBOO_CHEST_RAFT,
        )

    fun onUseItem(event: PlayerUseItemEvent) {
        if (event.isCancelled) return

        val player = event.player
        val held = player.getItemInHand(event.hand)
        val entityType = boatTypes[held.material()] ?: return
        if (player.gameMode == GameMode.SPECTATOR) return

        val eyePosition = player.position.add(0.0, player.eyeHeight, 0.0)
        val placementPosition =
            findWaterPlacementPosition(
                player.instance,
                eyePosition,
                eyePosition.direction(),
            ) ?: return

        val boat = Entity(entityType)
        boat.setNoGravity(true)
        if (!canPlaceAt(event.instance, placementPosition, boat.boundingBox)) return

        val spawn = boat.setInstance(event.instance, placementPosition)
        if (spawn == null) return

        event.isCancelled = true
        if (player.gameMode != GameMode.CREATIVE) {
            player.setItemInHand(event.hand, held.consume(1))
        }
    }

    fun onInteract(event: PlayerEntityInteractEvent) {
        val player = event.player
        val boat = event.target
        if (boat.entityType !in boatEntityTypes) return
        if (player.gameMode == GameMode.SPECTATOR || player.vehicle != null) return

        val capacity = if (boat.entityType in chestBoatEntityTypes) 1 else 2
        if (boat.passengers.size >= capacity) return

        boat.addPassenger(player)
    }

    fun onAttack(event: EntityAttackEvent) {
        val player = event.entity as? Player ?: return
        if (player.gameMode == GameMode.SPECTATOR) return

        val boat = event.target
        val material = boatItems[boat.entityType] ?: return
        val instance = boat.instance ?: return
        val position = boat.position

        boat.remove()
        if (player.gameMode != GameMode.CREATIVE) {
            Items.spawn(instance, position, ItemStack.of(material))
        }
    }

    internal fun entityType(material: Material): EntityType? = boatTypes[material]

    internal fun findWaterPlacementPosition(
        instance: Instance?,
        eyePosition: Pos,
        direction: Vec,
    ): Pos? {
        if (instance == null || direction.isZero) return null

        var distance = 0.0
        while (distance <= PLACEMENT_RANGE) {
            val point = eyePosition.add(direction.mul(distance))
            val block = instance.getBlock(point)
            if (block.compare(Block.WATER)) {
                return Pos(point.x, point.blockY() + 1.0, point.z, eyePosition.yaw, 0f)
            }
            val collisionShape = block.registry()?.collisionShape()
            if (collisionShape != null && !collisionShape.relativeEnd().isZero) return null
            distance += RAY_STEP
        }
        return null
    }

    private fun canPlaceAt(
        instance: Instance,
        position: Pos,
        boundingBox: BoundingBox,
    ): Boolean {
        val minX = floor(position.x + boundingBox.minX()).toInt()
        val maxX = floor(position.x + boundingBox.maxX() - Vec.EPSILON).toInt()
        val minY = floor(position.y + boundingBox.minY()).toInt()
        val maxY = floor(position.y + boundingBox.maxY() - Vec.EPSILON).toInt()
        val minZ = floor(position.z + boundingBox.minZ()).toInt()
        val maxZ = floor(position.z + boundingBox.maxZ() - Vec.EPSILON).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val blockPosition = Vec(x.toDouble(), y.toDouble(), z.toDouble())
                    val shape = instance.getBlock(x, y, z).registry()?.collisionShape() ?: continue
                    if (shape.intersectBox(position.sub(blockPosition), boundingBox)) return false
                }
            }
        }

        return instance.getNearbyEntities(position, 3.0).none { entity ->
            entity.hasEntityCollision() &&
                entity.boundingBox.intersectBox(entity.position.sub(position), boundingBox)
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerUseItemEvent::class.java, BoatListener::onUseItem)
        Vanilla.eventNode.addListener(PlayerEntityInteractEvent::class.java, BoatListener::onInteract)
        Vanilla.eventNode.addListener(EntityAttackEvent::class.java, BoatListener::onAttack)
    }
}
