package net.aechronis.combat.objects

import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.entity.metadata.golem.ShulkerMeta
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.play.EntityAttributesPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import java.util.concurrent.CompletableFuture

/** Spawned collision entities belong to one runtime and are never damage targets. */
internal class VehicleCollisionHitbox(
    private val owner: VehicleRuntime,
) {
    private class Part(
        val carrier: Entity,
        val shulker: VehicleCollisionEntity,
    ) {
        @Volatile
        private var removed = false
        private var spawnFuture = CompletableFuture.completedFuture<Void>(null)

        fun spawn(
            instance: Instance,
            position: Pos,
        ) {
            // Wait for both chunks before publishing the chain. Cleanup also waits for
            // registration, so an outstanding chunk load cannot resurrect removed helpers.
            spawnFuture = CompletableFuture.allOf(carrier.setInstance(instance, position), shulker.setInstance(instance, position))
            spawnFuture =
                spawnFuture.thenRun {
                    if (!removed) {
                        carrier.addPassenger(shulker)
                        carrier.isAutoViewable = true
                    }
                }
        }

        fun move(position: Pos) {
            if (!spawnFuture.isDone || removed) return
            spawnFuture.join()
            if (carrier.position != position) carrier.teleport(position)
        }

        fun remove() {
            removed = true
            spawnFuture.whenComplete { _, _ ->
                shulker.remove()
                carrier.remove()
            }
        }
    }

    private val parts = ArrayList<Part>()
    private var instance: Instance? = null

    fun update() {
        val body = owner.entity
        val targetInstance = body.instance
        if (body.isRemoved || targetInstance == null) {
            remove()
            return
        }
        val shape = owner.vehicle.collisionHitbox.at(body.position, owner.vehicle.hitboxRoll(body))
        if (instance !== targetInstance || parts.any { it.carrier.isRemoved || it.shulker.isRemoved }) {
            remove()
            instance = targetInstance
            try {
                shape.boxes.forEachIndexed { index, box ->
                    val carrier = Entity(EntityType.ITEM_DISPLAY)
                    val definition = owner.vehicle.collisionHitbox.parts[index]
                    val shulker = VehicleCollisionEntity(owner.entity, definition.scale)
                    val part = Part(carrier, shulker)
                    parts.add(part)
                    // Spawn the complete mount chain together. An independently tracked
                    // passenger can otherwise arrive before its carrier for late viewers.
                    carrier.isAutoViewable = false
                    shulker.isAutoViewable = false
                    carrier.setNoGravity(true)
                    carrier.setHasPhysics(false)
                    (carrier.entityMeta as ItemDisplayMeta).posRotInterpolationDuration = 3
                    carrier.updateViewableRule { VehicleRegistry.ride(it)?.runtime !== owner }
                    shulker.updateViewableRule { VehicleRegistry.ride(it)?.runtime !== owner }
                    // Passenger positioning bypasses the client's shulker block-grid snapping.
                    part.spawn(targetInstance, box.bottomCenter)
                }
            } catch (exception: Exception) {
                remove()
                throw exception
            }
        } else {
            parts.forEachIndexed { index, part ->
                part.move(shape.boxes[index].bottomCenter)
            }
        }
    }

    fun refreshViewers() {
        parts.forEach {
            it.carrier.updateViewableRule()
        }
    }

    fun remove() {
        val removed = parts.toList()
        parts.clear()
        instance = null
        removed.forEach { it.remove() }
    }
}

/**
 * Intentionally an Entity, not a LivingEntity: combat raycasts must continue using
 * the original vehicle hitbox. Send scale on every spawn, including late viewers.
 */
internal class VehicleCollisionEntity(
    val vehicleEntity: Entity,
    private val scale: Double,
) : Entity(EntityType.SHULKER) {
    init {
        setNoGravity(true)
        setHasPhysics(false)
        setBoundingBox(scale, scale, scale)
        (entityMeta as ShulkerMeta).apply {
            isInvisible = true
            isSilent = true
            isNoAi = true
            shieldHeight = 0
        }
    }

    override fun updateNewViewer(player: Player) {
        super.updateNewViewer(player)
        player.sendPacket(
            EntityAttributesPacket(
                entityId,
                listOf(EntityAttributesPacket.Property(Attribute.SCALE, scale, emptyList())),
            ),
        )
        if (player in Hitbox.viewingHitboxes) updateHitboxVisibility(player)
    }

    fun updateHitboxVisibility(player: Player) {
        val flags = metadata.get(MetadataDef.ENTITY_FLAGS).toInt()
        // Change only this viewer's invisibility bit; shared metadata stays invisible.
        val viewerFlags = if (player in Hitbox.viewingHitboxes) flags and 0x20.inv() else flags
        player.sendPacket(
            EntityMetaDataPacket(
                entityId,
                mapOf(MetadataDef.ENTITY_FLAGS.index() to Metadata.Byte(viewerFlags.toByte())),
            ),
        )
    }
}
