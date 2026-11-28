package net.aechronis.combat.objects

import net.aechronis.combat.utils.preparePacketBundle
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
import net.minestom.server.network.packet.server.play.EntityHeadLookPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.SetPassengersPacket
import java.util.concurrent.CompletableFuture

/** Spawned collision entities belong to one runtime and are never damage targets. */
internal class VehicleCollisionHitbox(
    private val owner: VehicleRuntime,
) {
    private class Part(
        val carrier: VehicleDisplayEntity,
        val shulker: VehicleCollisionEntity,
    ) {
        private val viewers = HashSet<Player>()

        @Volatile
        private var removed = false
        private var spawnFuture = CompletableFuture.completedFuture<Void>(null)

        /** Where the hull places this part; the entities lag behind it while nobody sees them. */
        private var target = Pos.ZERO

        fun spawn(
            instance: Instance,
            position: Pos,
        ) {
            target = position
            // Wait for both chunks before publishing the chain. Cleanup also waits for
            // registration, so an outstanding chunk load cannot resurrect removed helpers.
            spawnFuture = CompletableFuture.allOf(carrier.setInstance(instance, position), shulker.setInstance(instance, position))
            spawnFuture =
                spawnFuture.thenRun {
                    if (!removed) carrier.addPassenger(shulker)
                }
        }

        fun move(position: Pos) {
            target = position
            if (!spawnFuture.isDone || removed) return
            spawnFuture.join()
            // No client collides with a part nobody sees, so it waits behind until it is shown.
            // It still follows the hull into other chunks, staying loaded and tracked nearby.
            if (viewers.isNotEmpty() || !carrier.position.sameChunk(position)) {
                place()
            } else {
                updateParked()
            }
        }

        private fun place() {
            if (carrier.position != target) carrier.teleport(target)
            updateParked()
        }

        private fun updateParked() {
            // A part waiting behind must not stop players placing blocks where the hull was.
            val parked = carrier.position != target
            carrier.parked = parked
            shulker.parked = parked
        }

        fun refreshViewers(candidates: Candidates) {
            if (!spawnFuture.isDone || removed) return
            spawnFuture.join()
            if (viewers.isEmpty() && candidates.players.isEmpty()) return

            val iterator = viewers.iterator()
            while (iterator.hasNext()) {
                val player = iterator.next()
                if (player !in candidates) {
                    hide(player)
                    iterator.remove()
                }
            }

            val position = target.asVec()
            val box = shulker.boundingBox
            val min = position.add(box.relativeStart())
            val max = position.add(box.relativeEnd())
            for (index in candidates.players.indices) {
                val player = candidates.players[index]
                val bounds = candidates.bounds[index]
                val wasViewing = player in viewers
                val distance = if (wasViewing) HIDE_DISTANCE else SHOW_DISTANCE
                // Measure from the collision surfaces, including the player's height.
                val dx = maxOf(0.0, min.x - bounds.max.x, bounds.min.x - max.x)
                val dy = maxOf(0.0, min.y - bounds.max.y, bounds.min.y - max.y)
                val dz = maxOf(0.0, min.z - bounds.max.z, bounds.min.z - max.z)
                val nearby = dx * dx + dy * dy + dz * dz <= distance * distance
                if (nearby == wasViewing) continue
                if (nearby) {
                    place()
                    // Spawn the carrier first; the shulker bundles its spawn, metadata,
                    // scale, and passenger attachment for this manual viewer.
                    carrier.addViewer(player)
                    shulker.addViewer(player)
                    viewers.add(player)
                } else {
                    hide(player)
                    viewers.remove(player)
                }
            }
        }

        private fun hide(player: Player) {
            shulker.removeViewer(player)
            carrier.removeViewer(player)
        }

        fun remove() {
            removed = true
            viewers.clear()
            spawnFuture.whenComplete { _, _ ->
                shulker.remove()
                carrier.remove()
            }
        }
    }

    /** Players near the hull, flattened so each of thousands of parts can scan them cheaply. */
    private class Candidates(
        val players: Array<Player>,
        val bounds: Array<ShulkerHitbox.Box>,
    ) {
        private val members = players.toHashSet()

        operator fun contains(player: Player): Boolean = player in members
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
                    val carrier = VehicleDisplayEntity(owner.entity)
                    val definition = owner.vehicle.collisionHitbox.parts[index]
                    val shulker = VehicleCollisionEntity(owner.entity, definition.scale)
                    val part = Part(carrier, shulker)
                    parts.add(part)
                    // Stream complete pairs explicitly; automatic chunk tracking would
                    // expose distant helpers and does not refresh on every player step.
                    carrier.isAutoViewable = false
                    shulker.isAutoViewable = false
                    carrier.setNoGravity(true)
                    carrier.setHasPhysics(false)
                    carrier.entityMeta.isInvisible = true
                    (carrier.entityMeta as ItemDisplayMeta).posRotInterpolationDuration = 3
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
        refreshViewers()
    }

    fun refreshViewers() {
        // Every part lies within the shape's radius of the hull origin, so players farther
        // than that plus the hide distance cannot view any part; skip them before the per-part loop.
        val center = owner.entity.position.asVec()
        val reach = owner.vehicle.collisionHitbox.radius + HIDE_DISTANCE
        val players = ArrayList<Player>()
        val bounds = ArrayList<ShulkerHitbox.Box>()
        for (player in instance?.players.orEmpty()) {
            if (player.isRemoved || !player.autoViewEntities() || VehicleRegistry.ride(player)?.runtime === owner) continue
            val position = player.position.asVec()
            val box = player.boundingBox
            val playerBounds = ShulkerHitbox.Box(position.add(box.relativeStart()), position.add(box.relativeEnd()))
            val dx = maxOf(0.0, playerBounds.min.x - center.x, center.x - playerBounds.max.x)
            val dy = maxOf(0.0, playerBounds.min.y - center.y, center.y - playerBounds.max.y)
            val dz = maxOf(0.0, playerBounds.min.z - center.z, center.z - playerBounds.max.z)
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue
            players.add(player)
            bounds.add(playerBounds)
        }
        val candidates = Candidates(players.toTypedArray(), bounds.toTypedArray())
        parts.forEach { it.refreshViewers(candidates) }
    }

    fun remove() {
        val removed = parts.toList()
        parts.clear()
        instance = null
        removed.forEach { it.remove() }
    }

    companion object {
        // Prefetch before contact, then keep a wider exit band to avoid spawn/despawn
        // churn when a player or moving hull hovers near the streaming boundary.
        private const val SHOW_DISTANCE = 6.0
        private const val HIDE_DISTANCE = 8.0
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
    /** Set while the part waits at a stale position because nobody sees it. */
    @Volatile
    internal var parked = false

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
        // Entity.updateNewViewer sends spawn and metadata separately, exposing a
        // default-visible shulker if the client ticks between them during streaming.
        player.sendPacket(
            preparePacketBundle(
                player,
                buildList {
                    add(spawnPacket)
                    if (hasVelocity()) add(velocityPacket)
                    add(
                        EntityMetaDataPacket(
                            entityId,
                            metadataPacket.entries + (MetadataDef.ENTITY_FLAGS.index() to Metadata.Byte(viewerFlags(player))),
                        ),
                    )
                    add(
                        EntityAttributesPacket(
                            entityId,
                            listOf(EntityAttributesPacket.Property(Attribute.SCALE, scale, emptyList())),
                        ),
                    )
                    add(EntityHeadLookPacket(entityId, headRotation))
                    vehicle?.let { add(SetPassengersPacket(it.entityId, listOf(entityId))) }
                },
            ),
        )
    }

    override fun preventBlockPlacement(): Boolean = !parked && super.preventBlockPlacement()

    fun updateHitboxVisibility(player: Player) {
        player.sendPacket(
            EntityMetaDataPacket(
                entityId,
                mapOf(MetadataDef.ENTITY_FLAGS.index() to Metadata.Byte(viewerFlags(player))),
            ),
        )
    }

    private fun viewerFlags(player: Player): Byte {
        val flags = metadata.get(MetadataDef.ENTITY_FLAGS).toInt()
        // Change only this viewer's invisibility bit; shared metadata stays invisible.
        return (if (player in Hitbox.viewingHitboxes) flags and 0x20.inv() else flags).toByte()
    }
}
