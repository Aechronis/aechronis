package net.aechronis.nodes.objects

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.kyori.adventure.key.Key
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.network.packet.server.play.EntityAttributesPacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Encodes the viewer's relationship to a player in that player's client-side scale attribute.
 */
internal object RelationshipHitbox {
    private const val DEFAULT_PLAYER_SCALE = 1.0
    private const val DEFAULT_SCALE_EPSILON = 1.0e-9

    internal const val TOWN_SCALE = 0.994
    internal const val NATION_SCALE = 0.998
    internal const val ALLY_SCALE = 1.002
    internal const val NEUTRAL_SCALE = 1.006
    internal const val ENEMY_SCALE = 1.010

    private val markerId = Key.key("aechronis:relationship_hitbox")
    private val markerModifier = AttributeModifier(markerId, 0.0, AttributeOperation.ADD_VALUE)
    private val initialized = AtomicBoolean()
    private val pendingRepairs = ConcurrentHashMap.newKeySet<Repair>()

    @Volatile
    private var active = false

    private data class Repair(
        val viewerId: UUID,
        val entityId: Int,
    )

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        Nodes.eventNode.addListener(PlayerPacketOutEvent::class.java, this::onPacketOut)
    }

    fun start() {
        active = true
    }

    fun stop() {
        active = false
        pendingRepairs.clear()
    }

    fun refreshViewer(viewer: Player) {
        if (!active) return
        viewer.instance?.players?.forEach { target -> send(viewer, target) }
    }

    fun refreshTarget(target: Player) {
        if (!active) return
        target.viewers.forEach { viewer -> send(viewer, target) }
    }

    internal fun encodedScale(
        relationship: DiplomaticRelationship,
        actualScale: Double,
    ): Double? {
        if (abs(actualScale - DEFAULT_PLAYER_SCALE) > DEFAULT_SCALE_EPSILON) return null
        return when (relationship) {
            DiplomaticRelationship.TOWN -> TOWN_SCALE
            DiplomaticRelationship.NATION -> NATION_SCALE
            DiplomaticRelationship.ALLY -> ALLY_SCALE
            DiplomaticRelationship.NEUTRAL -> NEUTRAL_SCALE
            DiplomaticRelationship.ENEMY -> ENEMY_SCALE
        }
    }

    internal fun packet(
        entityId: Int,
        relationship: DiplomaticRelationship,
        actualScale: Double,
    ): EntityAttributesPacket? {
        val encodedScale = encodedScale(relationship, actualScale) ?: return null
        return EntityAttributesPacket(
            entityId,
            listOf(
                EntityAttributesPacket.Property(
                    Attribute.SCALE,
                    encodedScale,
                    listOf(markerModifier),
                ),
            ),
        )
    }

    private fun send(viewer: Player, target: Player) {
        if (
            viewer === target ||
            !viewer.isOnline ||
            !target.isOnline ||
            viewer.instance !== target.instance ||
            !target.isViewer(viewer)
        ) {
            return
        }

        val targetTown = Town.fromPlayer(target)
        val relationship = if (targetTown == null) {
            DiplomaticRelationship.NEUTRAL
        } else {
            Town.relationshipOfTownToTown(targetTown, Resident.fromPlayer(viewer)?.town)
        }
        packet(target.entityId, relationship, target.getAttributeValue(Attribute.SCALE))?.let(viewer::sendPacket)
    }

    private fun onPacketOut(event: PlayerPacketOutEvent) {
        if (!active) return
        val entityId = when (val packet = event.packet) {
            is SpawnEntityPacket -> {
                if (packet.type != EntityType.PLAYER) return
                packet.entityId
            }

            is EntityAttributesPacket -> {
                val scaleProperties = packet.properties.filter { property -> property.attribute == Attribute.SCALE }
                if (scaleProperties.isEmpty()) return
                if (scaleProperties.any { property -> property.modifiers.any { modifier -> modifier.id == markerId } }) return
                packet.entityId
            }

            else -> return
        }
        scheduleRepair(event.player.uuid, entityId)
    }

    private fun scheduleRepair(viewerId: UUID, entityId: Int) {
        val repair = Repair(viewerId, entityId)
        if (!pendingRepairs.add(repair)) return

        MinecraftServer.getSchedulerManager().scheduleNextTick {
            pendingRepairs.remove(repair)
            if (!active) return@scheduleNextTick
            val viewer = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(viewerId) ?: return@scheduleNextTick
            val target = viewer.instance?.getEntityById(entityId) as? Player ?: return@scheduleNextTick
            send(viewer, target)
        }
    }
}
